# 선착순 이벤트 설계: RateLimit 하나로 중복 참여를 막을 수 있을까?

대규모 선착순 캠페인 시스템을 설계하면서 가장 중요하게 고민했던 부분은 **"한 명의 유저가 재고를 여러 개 가져가는 중복 참여를 어떻게 완벽하게 막을 것인가?"** 였습니다.

초기에는 API 진입점에 있는 `RateLimit`(처리율 제한)의 TTL(만료 시간)을 영구적으로 설정하면, 한 번 들어온 유저를 평생 막을 수 있으니 가장 효율적이지 않을까 생각했습니다. 

하지만 분산 환경의 동시성(Concurrency)과 비즈니스 로직의 특성을 고려해 보았을 때, **RateLimit 하나만으로는 결코 데이터 정합성을 지킬 수 없다**는 결론에 도달했습니다.

그 이유를 3가지 치명적인 시나리오를 통해 설명해 보겠습니다.

---

## 1. 억울한 탈락자 발생 (False Positive)

가장 큰 문제는 **"성공하기 전"에 영원히 갇혀버리는 유저**가 생긴다는 점입니다.

RateLimit은 시스템을 보호하기 위해 비즈니스 로직(재고 차감 등)이 실행되기 전, **가장 바깥쪽에서** 트래픽을 거릅니다. 만약 RateLimit에 무제한 TTL을 걸었다면 이런 시나리오가 발생합니다.

```java
public void participate(Long campaignId, Long userId) {
    // 1. RateLimit 영구 도장 쾅! (이후 이 유저는 영원히 접근 불가)
    if (!rateLimitService.isAllowed(campaignId, userId)) { 
        throw new RateLimitExceededException(); 
    }

    // 2. 비즈니스 로직 진입
    long remaining = redisStockService.decrStock(campaignId);

    // 3. 앗, 재고가 없네? 또는 큐가 꽉 찼네? (당첨 실패)
    if (remaining < 0 || queueFull) {
        throw new StockExhaustedException();
    }
}
```

*   **문제 상황**: 유저가 버튼을 눌러 RateLimit을 통과해 '영구 도장'이 찍혔습니다. 하지만 간발의 차이로 재고가 소진되어 **당첨에는 실패**했습니다.
*   **결과**: 나중에 취소표가 생기거나, 큐가 비워져서 다시 참여할 기회가 생겨도 이 유저는 영원히 `RateLimitExceededException`에 막혀 **평생 다시는 이벤트에 참여할 수 없게 됩니다.**

## 2. 완벽히 동시에 들어오는 요청 (Race Condition)

만약 1번 문제를 어떻게든 해결했다고 쳐도, 분산 환경에서는 **동시성 문제**가 남습니다.

RateLimit의 `SETNX`(Set if Not eXists) 명령어 자체는 원자적(Atomic)이지만, Java 코드의 전체 흐름은 원자적이지 않습니다.

악의적인 유저가 매크로를 이용해 완벽히 동시에 2개의 스레드로 방어망을 뚫고 진입한다면 어떨까요?

```java
// 스레드 A: RateLimit 우회 진입 성공
long remainingA = redis.decr(stockKey); // 재고 1 차감

// 스레드 B: RateLimit 우회 진입 성공
long remainingB = redis.decr(stockKey); // 재고 1 차감
```

결국 두 스레드 모두 재고를 깎게 되고, 귀중한 선착순 재고를 혼자서 2~3개씩 쓸어가게 됩니다.

## 3. RateLimit과 비즈니스 룰은 목적 자체가 다름

시스템 아키텍처 관점에서 두 기능은 역할이 완전히 분리되어야 합니다.

*   **RateLimit (인프라적 방어)**: "짧은 시간에 비정상적으로 많이 때리는 행위(도배 공격)"를 막아 서버와 DB의 과부하를 막는 방패입니다. (짧은 임시 페널티)
*   **중복 참여 방지 (비즈니스적 방어)**: "이 유저가 혜택(당첨)을 이미 받았는가?"를 판단하여 정합성을 지키는 금고입니다. (영구 페널티)

---

## 해결책: 임시 방패 + Lua 원자성(Atomicity) 결합

그래서 저는 이 두 가지를 결합한 **"이중 방어 구조"**를 설계했습니다.

### 1단계: 가벼운 임시 방패 (RateLimit 10초)
API 최전방에 10초짜리 RateLimit을 두어, 초당 수천 번 버튼을 연타하는 트래픽을 가볍게 튕겨냅니다. 무거운 로직을 타지 않으므로 서버 자원을 아낄 수 있습니다.

### 2단계: 철벽 금고 (Redis Lua 스크립트)
RateLimit을 뚫고 들어온 요청은 Redis 내부에서 **단일 스레드로 동작하는 Lua 스크립트**를 만납니다.

```lua
-- check-decr-enqueue.lua (일부 발췌)

-- 1. 영구 중복 참여 검증 (혜택을 이미 받았는가?)
if redis.call('EXISTS', KEYS[5]) == 1 then
    return {-997, 0}
end

-- 2. 재고 차감 시도
local remaining = redis.call('DECR', KEYS[2])
if remaining < 0 then
    return {remaining, 0}
end

-- 3. 성공 시 영구 도장 쾅! (이 순간 확정)
redis.call('SET', KEYS[5], '1')
```

Lua 스크립트는 실행되는 수 밀리초 동안 다른 어떤 요청도 끼어들지 못하게 하는 **완벽한 원자성(Atomicity)**을 보장합니다.

1.  **당첨이 확정되는 그 찰나의 순간에만 영구 도장을 찍으므로**, 실패한 유저가 영구히 차단되는(False Positive) 문제가 없습니다.
2.  아무리 동시에 요청이 들어와도 Redis가 순차적으로 처리하므로, **중복 차감이 불가능**합니다.

## 결론

이중 방어선을 구축한 덕분에, **150만 트래픽의 극한 상황에서도 1명의 유저가 2개의 재고를 가져가는 일은 단 1건도 발생하지 않았습니다.** 

단순히 '막는다'는 생각에서 벗어나, 데이터의 라이프사이클과 동시성을 고려할 때 비로소 튼튼한 시스템을 설계할 수 있다는 것을 크게 배울 수 있었습니다.