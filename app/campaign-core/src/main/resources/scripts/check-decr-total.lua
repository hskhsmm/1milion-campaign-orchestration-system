-- KEYS[1] = active:campaign:{campaignId}  (캠페인별 active 플래그, 해시태그로 나머지 키와 동일 슬롯)
-- KEYS[2] = stock:campaign:{campaignId}   (동일 해시태그 → 동일 슬롯 보장)
-- KEYS[3] = total:campaign:{campaignId}   (동일 해시태그 → 동일 슬롯 보장)
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-999, 0}
end
local remaining = redis.call('DECR', KEYS[2])
if remaining == 0 then
    redis.call('DEL', KEYS[1])
end
local total = tonumber(redis.call('GET', KEYS[3])) or 0
return {remaining, total}
