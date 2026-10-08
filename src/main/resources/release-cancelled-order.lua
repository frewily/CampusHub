-- Only a COMMITTED cancellation outbox may invoke this script. Not an admission-failure refund.
-- KEYS: stock, activity metadata, accepted user->order, order->release marker.
-- ARGV: user ID, original order ID, original activity expiry milliseconds.
-- 0 released, 1 already released, 2 expired/no live inventory, 3 invalid state, 4 unmatched reservation.
redis.replicate_commands()
local deadline = tonumber(ARGV[3])
if not deadline or deadline ~= math.floor(deadline) or deadline < 1
        or ARGV[3] ~= string.format('%.0f', deadline) then return 3 end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now >= deadline then return 2 end -- do not resurrect expired activity keys
local expected = {'string', 'hash', 'hash', 'hash'}
for i, key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if (i < 4 and kind ~= expected[i]) or (i == 4 and kind ~= 'none' and kind ~= 'hash') then return 3 end
end
local prior = redis.call('HGET', KEYS[4], ARGV[2])
if prior then return prior == 'DONE:' .. ARGV[1] and 1 or 3 end
if redis.call('HGET', KEYS[2], 'expireAt') ~= ARGV[3] then return 3 end
if redis.call('HGET', KEYS[3], ARGV[1]) ~= ARGV[2] then return 4 end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not stock or stock < 0 or stock >= 2147483647 or stock ~= math.floor(stock)
        or (raw ~= '0' and not string.match(raw, '^[1-9]%d*$')) then return 3 end
-- Lua has no rollback. PENDING means the increment may or may not have happened.
-- Retry must require review, not increment again or claim completion without DONE.
redis.call('HSET', KEYS[4], ARGV[2], 'PENDING:' .. ARGV[1])
redis.call('PEXPIREAT', KEYS[4], ARGV[3])
redis.call('INCRBY', KEYS[1], 1)
redis.call('HSET', KEYS[4], ARGV[2], 'DONE:' .. ARGV[1])
return 0
