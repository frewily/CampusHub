-- KEYS: stock, legacy participants, activity metadata, accepted user->order, shared Stream.
-- ARGV: user ID, candidate order ID, trusted eligibility, DB status, DB begin/end, voucher ID.
-- IDs stay strings: Lua numbers cannot preserve all 64-bit order IDs.
redis.replicate_commands() -- Redis 6 compatibility: TIME-based writes replicate their effects.
if #KEYS ~= 5 or #ARGV ~= 7 then return {8, ''} end
local function kind(key)
    return redis.call('TYPE', key).ok
end
local expected = {'string', 'set', 'hash', 'hash', 'stream'}
for index, key in ipairs(KEYS) do
    local actual = kind(key)
    if actual ~= 'none' and actual ~= expected[index] then return {8, ''} end
end
if ARGV[3] ~= '1' then return {7, ''} end
local previous = redis.call('HGET', KEYS[4], ARGV[1])
if previous then return {9, previous} end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return {2, ''} end

if ARGV[4] == '3' then return {5, ''} end
if ARGV[4] ~= '1' then return {6, ''} end
local databaseBegin, databaseEnd = tonumber(ARGV[5]), tonumber(ARGV[6])
if not databaseBegin or not databaseEnd or databaseEnd <= databaseBegin then return {8, ''} end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now < databaseBegin then return {4, ''} end
if now >= databaseEnd then return {5, ''} end

local metadata = redis.call('HMGET', KEYS[3], 'status', 'beginAt', 'endAt', 'expireAt')
if not metadata[1] or not metadata[2] or not metadata[3] or not metadata[4] then return {8, ''} end
if metadata[1] == '3' then return {5, ''} end
if metadata[1] ~= '1' then return {6, ''} end
if metadata[2] ~= ARGV[5] or metadata[3] ~= ARGV[6] then return {8, ''} end
local beginAt, endAt, expireAt = tonumber(metadata[2]), tonumber(metadata[3]), tonumber(metadata[4])
local rawStock = redis.call('GET', KEYS[1])
local stock = tonumber(rawStock)
if not beginAt or not endAt or endAt <= beginAt or not expireAt or expireAt <= endAt
        or expireAt ~= endAt + 86400000 or metadata[4] ~= string.format('%.0f', expireAt)
        or not stock or stock < 0 or stock > 2147483647 or stock ~= math.floor(stock)
        or (rawStock ~= '0' and not string.match(rawStock, '^[1-9]%d*$')) then return {8, ''} end
if stock == 0 then return {1, ''} end

-- Preflight all key types and append before reserving: XADD failure must not reduce stock.
local event = redis.pcall('XADD', KEYS[5], '*', 'userId', ARGV[1], 'voucherId', ARGV[7], 'id', ARGV[2])
if type(event) == 'table' and event.err then return {8, ''} end
redis.call('INCRBY', KEYS[1], -1)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[4], ARGV[1], ARGV[2])
for index = 1, 4 do redis.call('PEXPIREAT', KEYS[index], metadata[4]) end
return {0, ARGV[2]}
