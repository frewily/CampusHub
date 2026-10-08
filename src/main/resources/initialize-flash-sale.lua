-- KEYS: stock, legacy participants, activity metadata, accepted user->order mapping.
-- ARGV: initial stock, begin/end UTC epoch milliseconds, absolute retention deadline.
redis.replicate_commands()
if #KEYS ~= 4 or #ARGV ~= 4 then return 2 end
for _, key in ipairs(KEYS) do
    if redis.call('EXISTS', key) == 1 then
        return 1 -- Never reset a live reservation or its deduplication records.
    end
end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local stock, beginAt, endAt, expireAt = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
if not stock or stock <= 0 or stock > 2147483647 or stock ~= math.floor(stock)
        or not string.match(ARGV[1], '^[1-9]%d*$') or not beginAt or not endAt
        or beginAt < 1000 or endAt > 2147483647000 or endAt <= beginAt
        or not expireAt or expireAt ~= endAt + 86400000 or expireAt <= now
        or ARGV[2] ~= string.format('%.0f', beginAt) or ARGV[3] ~= string.format('%.0f', endAt)
        or ARGV[4] ~= string.format('%.0f', expireAt) then
    return 2
end
redis.call('SET', KEYS[1], ARGV[1])
redis.call('PEXPIREAT', KEYS[1], ARGV[4])
redis.call('HSET', KEYS[3], 'status', '1', 'beginAt', ARGV[2], 'endAt', ARGV[3], 'expireAt', ARGV[4])
redis.call('PEXPIREAT', KEYS[3], ARGV[4])
return 0
