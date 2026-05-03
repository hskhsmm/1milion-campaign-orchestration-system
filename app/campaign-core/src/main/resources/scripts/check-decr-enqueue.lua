-- KEYS[1] = active:campaign:{campaignId}   (캠페인별 active 플래그)
-- KEYS[2] = stock:campaign:{campaignId}    (재고)
-- KEYS[3] = total:campaign:{campaignId}    (전체 재고 — sequence 계산용)
-- KEYS[4] = queue:campaign:{campaignId}    (Redis Queue)
-- ARGV[1] = maxQueueSize
-- ARGV[2] = campaignId
-- ARGV[3] = userId
-- 모든 KEYS는 {campaignId} 해시태그로 동일 슬롯 보장 (ElastiCache CME 필수 조건)
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-999, 0}
end
if tonumber(redis.call('LLEN', KEYS[4])) >= tonumber(ARGV[1]) then
    return {-998, 0}
end
local remaining = redis.call('DECR', KEYS[2])
if remaining < 0 then
    return {remaining, 0}
end
if remaining == 0 then
    redis.call('DEL', KEYS[1])
end
local total = tonumber(redis.call('GET', KEYS[3])) or 0
local sequence = total - remaining
local message = '{"campaignId":' .. ARGV[2] .. ',"userId":' .. ARGV[3] .. ',"sequence":' .. sequence .. '}'
redis.call('LPUSH', KEYS[4], message)
return {remaining, total}
