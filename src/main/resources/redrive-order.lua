-- Operator-only forward compensation. Review DB first; never refund reservation here.
-- KEYS: original stream, dead-letter stream, dead-letter->new-source-ID hash.
-- ARGV: dead-letter ID, operator label, reason code. Retrying this ID is idempotent.
redis.replicate_commands()
local expected = {'stream', 'stream', 'hash'}
for i, key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= expected[i] then return redis.error_reply('ORDER_STATE_WRONGTYPE') end
end
for i = 2, 3 do
    if not ARGV[i] or #ARGV[i] < 1 or #ARGV[i] > 64 or not string.match(ARGV[i], '^[%w_.-]+$') then
        return redis.error_reply('ORDER_AUDIT_LABEL_INVALID')
    end
end
local prior = redis.call('HGET', KEYS[3], ARGV[1])
if prior then return prior end
local entry = redis.call('XRANGE', KEYS[2], ARGV[1], ARGV[1])
if #entry ~= 1 then return redis.error_reply('ORDER_DEAD_LETTER_MISSING') end
local fields = entry[1][2]
local payload
for i = 1, #fields, 2 do if fields[i] == 'payload' then payload = fields[i + 1] end end
if not payload then return redis.error_reply('ORDER_PAYLOAD_MISSING') end
local ok, values = pcall(cjson.decode, payload)
if not ok or type(values) ~= 'table' or #values ~= 6 then return redis.error_reply('ORDER_PAYLOAD_INVALID') end
local data = {}
local maximum = '9223372036854775807'
for i = 1, #values, 2 do
    local key, value = values[i], values[i + 1]
    if (key ~= 'id' and key ~= 'userId' and key ~= 'voucherId') or data[key]
            or type(value) ~= 'string' or not string.match(value, '^[1-9]%d*$')
            or #value > #maximum or (#value == #maximum and value > maximum) then
        return redis.error_reply('ORDER_PAYLOAD_INVALID')
    end
    data[key] = value
end
if not data.id or not data.userId or not data.voucherId then return redis.error_reply('ORDER_PAYLOAD_INVALID') end
local newId = redis.call('XADD', KEYS[1], '*', 'id', data.id, 'userId', data.userId, 'voucherId', data.voucherId)
local clock = redis.call('TIME')
redis.call('HSET', KEYS[3], ARGV[1], newId, ARGV[1] .. ':operator', ARGV[2],
    ARGV[1] .. ':reason', ARGV[3], ARGV[1] .. ':at', clock[1])
return newId
