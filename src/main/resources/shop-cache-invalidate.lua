-- KEYS v2 data, persistent epoch, legacy data; ARGV a NEW random epoch on every invocation.
local kind = redis.call('TYPE', KEYS[2]).ok
if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('SHOP_CACHE_WRONGTYPE') end
-- Epoch first: old entries are ignored even if DEL subsequently fails. Lua has no rollback.
redis.call('SET', KEYS[2], ARGV[1])
redis.call('DEL', KEYS[1], KEYS[3])
return 1
