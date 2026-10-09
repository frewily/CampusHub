-- KEYS data, epoch, lock; ARGV lease token, observed epoch, envelope, physical TTL ms.
for _, key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('SHOP_CACHE_WRONGTYPE') end
end
local ttl = tonumber(ARGV[4])
if not ttl or ttl < 1 or ttl > 75000 or ttl ~= math.floor(ttl) then
    return redis.error_reply('SHOP_CACHE_INVALID_TTL')
end
if redis.call('GET', KEYS[3]) ~= ARGV[1] or (redis.call('GET', KEYS[2]) or '') ~= ARGV[2] then return 0 end
redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
return 1
