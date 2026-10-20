-- Queue와 in-flight는 같은 {campaignId} 슬롯에 있어야 한다.
-- Kafka 확인 전에는 원본을 Redis에서 없애지 않는다.
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[2]) then
    return nil
end

local message = redis.call('LINDEX', KEYS[1], -1)
if not message then
    return nil
end

local claim = ARGV[1] .. '|' .. message
local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
-- 먼저 복구 사본을 만든다. ZADD가 실패해도 원본은 Queue에 남는다.
redis.call('ZADD', KEYS[2], nowMillis, claim)
redis.call('RPOP', KEYS[1])
return claim
