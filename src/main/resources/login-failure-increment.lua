local failures = redis.call('incr', KEYS[1])
if failures == 1 then
    redis.call('expire', KEYS[1], ARGV[1])
end
return failures
