-- KEYS[1] = active:campaigns
-- KEYS[2] = stock:campaign:{id}
-- KEYS[3] = total:campaign:{id}
-- ARGV[1] = campaignId (string)
if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then
    return {-999, 0}
end
local remaining = redis.call('DECR', KEYS[2])
if remaining == 0 then
    redis.call('SREM', KEYS[1], ARGV[1])
end
local total = tonumber(redis.call('GET', KEYS[3])) or 0
return {remaining, total}
