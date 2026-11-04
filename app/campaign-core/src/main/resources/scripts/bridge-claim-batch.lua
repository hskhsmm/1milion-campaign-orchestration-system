-- Queue 꼬리(가장 오래된 쪽)에서 최대 ARGV[3]건을 in-flight로 옮긴다.
-- KEYS[1]=queue(List), KEYS[2]=in-flight(ZSet). 같은 {campaignId} 슬롯이어야 한다.
-- ARGV[1]=batch 토큰, ARGV[2]=in-flight 상한, ARGV[3]=요청 건수
-- 모든 검증을 쓰기 전에 끝낸다. 쓰기는 ZADD 1회 → LTRIM 1회뿐이다.
-- ZADD가 실패하면(OOM 등) 원본은 Queue에 그대로 남는다.
local function positiveInteger(value)
    local number = tonumber(value)
    if not number or number < 1 or number ~= math.floor(number) then
        return nil
    end
    return number
end

local requested = positiveInteger(ARGV[3])
if not requested then
    return redis.error_reply('ERR bridge claim batch size must be a positive integer')
end
local maxInFlight = positiveInteger(ARGV[2])
if not maxInFlight then
    return redis.error_reply('ERR bridge in-flight limit must be a positive integer')
end
if not ARGV[1] or ARGV[1] == '' or string.find(ARGV[1], '|', 1, true) then
    return redis.error_reply('ERR bridge claim token must be non-empty and must not contain |')
end

local queueType = redis.call('TYPE', KEYS[1])['ok']
if queueType ~= 'list' and queueType ~= 'none' then
    return redis.error_reply('WRONGTYPE bridge queue is not a list')
end
local inFlightType = redis.call('TYPE', KEYS[2])['ok']
if inFlightType ~= 'zset' and inFlightType ~= 'none' then
    return redis.error_reply('WRONGTYPE bridge in-flight is not a sorted set')
end

local room = maxInFlight - redis.call('ZCARD', KEYS[2])
local k = math.min(requested, room, redis.call('LLEN', KEYS[1]))
if k <= 0 then
    return {}
end

-- LPUSH로 쌓였으므로 LRANGE -k -1은 [새것 ... 가장 오래된 것] 순서다. 뒤에서부터 읽어 오래된 순으로 만든다.
local messages = redis.call('LRANGE', KEYS[1], -k, -1)
if #messages ~= k then
    return redis.error_reply('ERR bridge queue changed during claim')
end

local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local claims = {}
local zaddArgs = {}
for i = k, 1, -1 do
    local claim = ARGV[1] .. ':' .. (k - i) .. '|' .. messages[i]
    claims[#claims + 1] = claim
    zaddArgs[#zaddArgs + 1] = nowMillis
    zaddArgs[#zaddArgs + 1] = claim
end
if #claims ~= k or #zaddArgs ~= k * 2 then
    return redis.error_reply('ERR bridge claim arguments were not built')
end

-- 첫 쓰기. 한 명령이므로 k건 전부 등록되거나 전부 실패한다.
redis.call('ZADD', KEYS[2], unpack(zaddArgs))
redis.call('LTRIM', KEYS[1], 0, -(k + 1))
return claims
