-- Atomic epoch/data snapshot. No epoch yet is the empty-string initial generation.
for _, key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('SHOP_CACHE_WRONGTYPE') end
end
return {redis.call('GET', KEYS[2]) or '', redis.call('GET', KEYS[1]) or ''}
