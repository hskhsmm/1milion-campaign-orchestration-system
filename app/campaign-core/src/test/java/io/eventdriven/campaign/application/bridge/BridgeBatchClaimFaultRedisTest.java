package io.eventdriven.campaign.application.bridge;

import io.eventdriven.campaign.application.service.RedisStockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 실제 Redis에서 batch claim의 응답 유실과 여러 Bridge 동시 claim 경계를 확인한다.
 * localhost 전용, 이 시험이 만든 고유 키만 지운다.
 */
@EnabledIfEnvironmentVariable(named = "BRIDGE_TEST_REDIS_PORT", matches = "[0-9]+")
class BridgeBatchClaimFaultRedisTest {

    private static final int MAX_IN_FLIGHT = 10_000;
    private static final String ACTIVE_CAMPAIGNS = "active:campaigns";

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private long campaignId;
    private String queue;
    private String inFlight;

    @BeforeEach
    void setUp() {
        redis = new StringRedisTemplate(newFactory());
        registry = new SimpleMeterRegistry();
        campaignId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        queue = "queue:campaign:{" + campaignId + "}";
        inFlight = BridgeQueueService.inFlightKey(campaignId);
    }

    @AfterEach
    void tearDown() {
        redis.delete(List.of(queue, inFlight));
        redis.opsForSet().remove(ACTIVE_CAMPAIGNS, String.valueOf(campaignId));
        registry.close();
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    private LettuceConnectionFactory newFactory() {
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("BRIDGE_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        factory.start();
        factories.add(factory);
        return factory;
    }

    /** 각 Bridge 인스턴스처럼 자기 연결을 가진 서비스. */
    private BridgeQueueService newBridgeInstance() {
        return new BridgeQueueService(new StringRedisTemplate(newFactory()), registry);
    }

    private List<String> enqueue(int count) {
        List<String> messages = IntStream.rangeClosed(1, count).mapToObj(i -> "{\"seq\":" + i + "}").toList();
        // leftPushAll도 앞 원소부터 LPUSH하므로 seq 1이 가장 오래된 쪽(꼬리)에 놓인다.
        redis.opsForList().leftPushAll(queue, messages);
        return messages;
    }

    private void expireAllClaims() {
        Set<String> claims = redis.opsForZSet().range(inFlight, 0, -1);
        claims.forEach(claim -> redis.opsForZSet().add(inFlight, claim, 0));
    }

    private double count(String name, String tag, String value) {
        var counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---------------------------------------------------------------------------------------------
    // 응답 유실: Redis는 ZADD+LTRIM을 끝냈지만 앱은 응답을 받지 못함

    /** 실제 Redis에서 스크립트를 실행한 뒤, 지정한 회차에만 응답 대신 예외를 던진다. */
    static class ResponseLossRedisTemplate extends StringRedisTemplate {
        final AtomicBoolean loseNextScriptResponse = new AtomicBoolean();

        ResponseLossRedisTemplate(RedisConnectionFactory factory) {
            super(factory);
        }

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            T result = super.execute(script, keys, args);
            if (loseNextScriptResponse.compareAndSet(true, false)) {
                throw new RedisSystemException("simulated response loss after script completed", null);
            }
            return result;
        }
    }

    @Test
    void lostClaimResponseKeepsEventsInFlightWithoutKafkaSendAndReclaimRestoresAll() {
        List<String> originals = enqueue(50);
        redis.opsForSet().add(ACTIVE_CAMPAIGNS, String.valueOf(campaignId));
        var lossy = new ResponseLossRedisTemplate(newFactory());
        lossy.afterPropertiesSet();
        var service = new BridgeQueueService(lossy, registry);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        RedisStockService stock = mock(RedisStockService.class);
        when(stock.isActive(campaignId)).thenReturn(true);
        var bridge = new ParticipationBridge(lossy, kafka, service, Runnable::run, stock, registry,
                mock(JsonMapper.class), 50);

        lossy.loseNextScriptResponse.set(true);
        bridge.drainQueues(); // claim 예외는 drainQueues가 기록하고 다음 회차로 넘긴다

        // 응답 유실 직후
        assertThat(redis.opsForList().size(queue)).isZero();
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(50);
        verifyNoInteractions(kafka);
        assertThat(count("bridge.redis.errors", "operation", "claim")).isEqualTo(1);
        assertThat(registry.get("bridge.claim.events").counter().count()).isZero();

        // lease 만료 후 reclaim
        expireAllClaims();
        assertThat(service.reclaimExpired(campaignId)).isEqualTo(50);
        assertThat(redis.opsForList().size(queue)).isEqualTo(50);
        assertThat(redis.opsForZSet().zCard(inFlight)).isZero();

        // 재처리
        var retry = service.claimBatch(campaignId, 50);
        assertThat(retry).extracting(BridgeQueueService.Claim::message).containsExactlyInAnyOrderElementsOf(originals);
        assertThat(redis.opsForList().size(queue)).isZero();
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(50);
    }

    // ---------------------------------------------------------------------------------------------
    // 여러 Bridge 동시 claim

    private static <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<java.util.concurrent.Callable<T>> task)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task.apply(i)));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** in-flight ZCARD를 계속 읽어 최댓값을 기록한다. Lua가 원자적이므로 관측 최댓값이 상한을 넘으면 위반이다. */
    private Thread startSampler(AtomicBoolean running, AtomicLong maxInFlight) {
        var sampler = new StringRedisTemplate(newFactory());
        Thread thread = new Thread(() -> {
            while (running.get()) {
                Long size = sampler.opsForZSet().zCard(inFlight);
                if (size != null) {
                    maxInFlight.accumulateAndGet(size, Math::max);
                }
            }
        });
        thread.start();
        return thread;
    }

    private static void assertUnique(List<BridgeQueueService.Claim> claims, int expected) {
        assertThat(claims).hasSize(expected);
        assertThat(new HashSet<>(claims.stream().map(BridgeQueueService.Claim::value).toList())).hasSize(expected);
        assertThat(new HashSet<>(claims.stream().map(BridgeQueueService.Claim::message).toList())).hasSize(expected);
    }

    /** 시험 A: 5,000건을 8개 Bridge가 N=50으로 동시에 모두 가져간다. */
    @RepeatedTest(20)
    void concurrentBridgesDrainWholeQueueWithoutDuplicateOrLoss() throws Exception {
        List<String> originals = enqueue(5_000);
        List<BridgeQueueService.Claim> collected = new ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(8);

        runConcurrently(8, i -> {
            BridgeQueueService service = newBridgeInstance();
            return () -> {
                List<BridgeQueueService.Claim> mine = new ArrayList<>();
                start.await();
                List<BridgeQueueService.Claim> batch;
                while (!(batch = service.claimBatch(campaignId, 50)).isEmpty()) {
                    mine.addAll(batch);
                }
                return mine;
            };
        }).forEach(collected::addAll);

        assertUnique(collected, 5_000);
        assertThat(collected).extracting(BridgeQueueService.Claim::message).containsExactlyInAnyOrderElementsOf(originals);
        assertThat(redis.opsForList().size(queue)).isZero();
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(5_000);
        assertThat(count("bridge.redis.errors", "operation", "claim")).isZero();
    }

    /** 시험 B: 20,000건, 상한 10,000에서 동시 claim해도 정확히 가장 오래된 10,000건만 in-flight로 간다. */
    @RepeatedTest(20)
    void concurrentBridgesNeverExceedInFlightLimit() throws Exception {
        List<String> originals = enqueue(20_000);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong maxInFlight = new AtomicLong();
        Thread sampler = startSampler(running, maxInFlight);
        List<BridgeQueueService.Claim> collected = new ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(8);
        try {
            runConcurrently(8, i -> {
                BridgeQueueService service = newBridgeInstance();
                return () -> {
                    List<BridgeQueueService.Claim> mine = new ArrayList<>();
                    start.await();
                    List<BridgeQueueService.Claim> batch;
                    while (!(batch = service.claimBatch(campaignId, 50)).isEmpty()) {
                        mine.addAll(batch);
                    }
                    return mine;
                };
            }).forEach(collected::addAll);
        } finally {
            running.set(false);
            sampler.join();
        }

        assertUnique(collected, MAX_IN_FLIGHT);
        assertThat(collected).extracting(BridgeQueueService.Claim::message)
                .containsExactlyInAnyOrderElementsOf(originals.subList(0, MAX_IN_FLIGHT));
        assertThat(redis.opsForZSet().zCard(inFlight)).isEqualTo(MAX_IN_FLIGHT);
        assertThat(redis.opsForList().size(queue)).isEqualTo(10_000);
        assertThat(redis.opsForList().range(queue, 0, -1))
                .containsExactlyInAnyOrderElementsOf(originals.subList(MAX_IN_FLIGHT, 20_000));
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(MAX_IN_FLIGHT);
        assertThat(count("bridge.redis.errors", "operation", "claim")).isZero();
    }

    /** 시험 C: claim 4개와 ACK 4개가 동시에 돈다. ACK가 다른 claim을 지우거나 상한을 넘지 않는다. */
    @RepeatedTest(20)
    void concurrentClaimAndAckDrainEverythingExactlyOnce() throws Exception {
        int total = 20_000;
        List<String> originals = enqueue(total);
        BlockingQueue<BridgeQueueService.Claim> handoff = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<BridgeQueueService.Claim> claimed = new ConcurrentLinkedQueue<>();
        AtomicInteger claimersDone = new AtomicInteger();
        AtomicInteger ackFalse = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong maxInFlight = new AtomicLong();
        Thread sampler = startSampler(running, maxInFlight);
        CyclicBarrier start = new CyclicBarrier(8);
        try {
            runConcurrently(8, i -> {
                BridgeQueueService service = newBridgeInstance();
                StringRedisTemplate own = new StringRedisTemplate(newFactory());
                if (i < 4) {
                    return () -> {
                        start.await();
                        while (true) {
                            List<BridgeQueueService.Claim> batch = service.claimBatch(campaignId, 50);
                            if (batch.isEmpty()) {
                                if (own.opsForList().size(queue) == 0) {
                                    break;
                                }
                                Thread.onSpinWait(); // in-flight 상한. ACK가 자리를 비울 때까지 다시 시도
                                continue;
                            }
                            claimed.addAll(batch);
                            handoff.addAll(batch);
                        }
                        claimersDone.incrementAndGet();
                        return null;
                    };
                }
                return () -> {
                    start.await();
                    while (true) {
                        BridgeQueueService.Claim claim = handoff.poll(10, TimeUnit.MILLISECONDS);
                        if (claim == null) {
                            if (claimersDone.get() == 4 && handoff.isEmpty()) {
                                break;
                            }
                            continue;
                        }
                        if (!service.acknowledge(claim)) {
                            ackFalse.incrementAndGet();
                        }
                    }
                    return null;
                };
            });
        } finally {
            running.set(false);
            sampler.join();
        }

        List<BridgeQueueService.Claim> all = new ArrayList<>(claimed);
        assertUnique(all, total);
        assertThat(all).extracting(BridgeQueueService.Claim::message).containsExactlyInAnyOrderElementsOf(originals);
        assertThat(ackFalse.get()).isZero();
        assertThat(count("bridge.claim.ack.results", "result", "removed")).isEqualTo(total);
        assertThat(count("bridge.claim.ack.results", "result", "not_found")).isZero();
        assertThat(count("bridge.claim.ack.results", "result", "error")).isZero();
        assertThat(redis.opsForList().size(queue)).isZero();
        assertThat(redis.opsForZSet().zCard(inFlight)).isZero();
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(MAX_IN_FLIGHT);
        assertThat(count("bridge.redis.errors", "operation", "claim")).isZero();
    }
}
