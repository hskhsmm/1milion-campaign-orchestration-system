-- 만료된 claim만 원자적으로 큐에 되돌린다. 늦은 Kafka callback의 ZREM과 경합해도 한쪽만 이긴다.
local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local deadline = nowMillis - tonumber(ARGV[1])
local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', deadline, 'LIMIT', 0, ARGV[2])
local restored = 0
for _, claim in ipairs(expired) do
    local separator = string.find(claim, '|', 1, true)
    -- 복구본을 먼저 쓴다. RPUSH가 실패하면 claim은 그대로 남는다.
    redis.call('RPUSH', KEYS[1], string.sub(claim, separator + 1))
    redis.call('ZREM', KEYS[2], claim)
    restored = restored + 1
end
return restored
