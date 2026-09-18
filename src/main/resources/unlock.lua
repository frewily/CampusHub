---@diagnostic disable: undefined-global
-- global redis, KEYS, ARGV

--比较线程标示与锁中的标示是否一致
if(redis.call('get',KEYS[1]) == ARGV[1])  then
    -- 释放锁 dey key
    return redis.call('del', KEYS[1])
end
return 0;