-- Renew only the same, still-live identity observed before SQL role checks.
if #KEYS ~= 1 or #ARGV ~= 2 then
    return 0
end
local ttl = tonumber(ARGV[2])
if not ttl or ttl <= 0 or ttl ~= math.floor(ttl) or ttl > 9007199254740991 then
    return 0
end
local kind = redis.call('TYPE', KEYS[1])
if type(kind) == 'table' then kind = kind.ok end
if kind ~= 'hash' or redis.call('HGET', KEYS[1], 'id') ~= ARGV[1] then
    return 0
end
if redis.call('PTTL', KEYS[1]) <= 0 then
    -- Invalid presented sessions are rejected even if cleanup is denied.
    redis.pcall('DEL', KEYS[1])
    return 0
end
if redis.call('PEXPIRE', KEYS[1], ttl) == 1 then
    return 1
end
return 0
