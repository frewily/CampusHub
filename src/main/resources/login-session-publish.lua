-- Only publish identity after this newly-created Hash has a TTL.
-- Redis scripts are isolated, not rollback transactions: cleanup errors may leave
-- a nickname-only placeholder, which the existing authentication filter rejects.
if #KEYS ~= 1 or #ARGV ~= 4 then
    return -2
end
local ttl = tonumber(ARGV[1])
if not ttl or ttl <= 0 or ttl ~= math.floor(ttl) or ttl > 9007199254740991 then
    return -2
end
local key = KEYS[1]
if redis.call('EXISTS', key) ~= 0 then
    return 0
end

redis.call('HSET', key, 'nickName', '')
local expiry = redis.pcall('PEXPIRE', key, ttl)
if type(expiry) == 'table' or expiry ~= 1 then
    redis.pcall('DEL', key)
    return -1
end

local published = redis.pcall('HSET', key, 'id', ARGV[2], 'nickName', ARGV[3], 'icon', ARGV[4])
if type(published) == 'table' then
    redis.pcall('DEL', key)
    return -1
end
return 1
