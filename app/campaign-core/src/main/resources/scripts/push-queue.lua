 -- Queue 크기 체크 후 LPUSH 원자적 실행
  local size = redis.call('LLEN', KEYS[1])
  if tonumber(size) >= tonumber(ARGV[1]) then
      return 0
  end
  redis.call('LPUSH', KEYS[1], ARGV[2])
  return 1
