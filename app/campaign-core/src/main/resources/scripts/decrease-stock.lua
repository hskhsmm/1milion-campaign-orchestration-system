-- 재고 차감 Lua 스크립트 (원자적 연산)
--
-- 반환값:
--   0 이상: 차감 후 남은 재고 (성공)
--   -1: 재고 부족 또는 키 없음 (실패)

local stock = redis.call('GET', KEYS[1])
if stock == false then
    return -1
end
if tonumber(stock) > 0 then
    return redis.call('DECR', KEYS[1])
else
    return -1
end
