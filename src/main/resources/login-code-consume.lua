local cachedCode = redis.call('get', KEYS[1])
if cachedCode == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
