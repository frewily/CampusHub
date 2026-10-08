-- KEYS: source stream, attempt hash, dead-letter stream, source->dead-letter index.
-- ARGV: action, group, consumer, source ID, max attempts, safe error classification.
-- Lua is atomic but NOT rollback-capable: validate types/values before any write.
redis.replicate_commands()
local action, group, consumer, id = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
if action ~= 'BEGIN' and action ~= 'SUCCESS' and action ~= 'FAILURE' then
    return redis.error_reply('ORDER_INVALID_ACTION')
end
local expected = {'stream', 'hash', 'stream', 'hash'}
-- A broken DLQ must prevent archival, not block unrelated successful order processing.
for i = 1, action == 'FAILURE' and 4 or 2 do
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= expected[i] then return redis.error_reply('ORDER_STATE_WRONGTYPE') end
end
local maximum = tonumber(ARGV[5])
if not maximum or maximum < 1 or maximum > 100 then return redis.error_reply('ORDER_INVALID_BUDGET') end
local pending = redis.call('XPENDING', KEYS[1], group, id, id, 1)
if #pending == 0 or pending[1][2] ~= consumer then return -1 end -- stale worker must not ACK/archive
local raw = redis.call('HGET', KEYS[2], id)
local attempts = tonumber(raw or '0')
if not attempts or attempts < 0 or attempts ~= math.floor(attempts) or attempts > 100 then
    return redis.error_reply('ORDER_INVALID_ATTEMPTS')
end
if action == 'BEGIN' then
    if attempts >= maximum then return 0 end
    return redis.call('HINCRBY', KEYS[2], id, 1) -- crash attempts count; not just caught exceptions
end
if action == 'SUCCESS' then
    redis.call('XACK', KEYS[1], group, id)
    redis.call('HDEL', KEYS[2], id)
    return 1
end
if action ~= 'FAILURE' then return redis.error_reply('ORDER_INVALID_ACTION') end
if attempts < maximum then return 0 end -- keep pending, other work may progress
local deadId = redis.call('HGET', KEYS[4], id)
if not deadId then
    local original = redis.call('XRANGE', KEYS[1], id, id)
    if #original == 0 then return redis.error_reply('ORDER_PAYLOAD_MISSING') end
    local clock = redis.call('TIME')
    -- Append BEFORE ACK. If XADD fails (e.g. exhausted stream ID), pending stays recoverable.
    deadId = redis.call('XADD', KEYS[3], '*', 'sourceId', id, 'group', group, 'consumer', consumer,
        'attempts', tostring(attempts), 'errorClass', ARGV[6], 'failedAt', clock[1],
        'payload', cjson.encode(original[1][2]), 'reservation', 'RETAINED')
    redis.call('HSET', KEYS[4], id, deadId)
else
    local archived = redis.call('XRANGE', KEYS[3], deadId, deadId)
    if #archived ~= 1 then return redis.error_reply('ORDER_ARCHIVE_INDEX_CORRUPT') end
    local matching = false
    for i = 1, #archived[1][2], 2 do
        if archived[1][2][i] == 'sourceId' and archived[1][2][i + 1] == id then matching = true end
    end
    if not matching then return redis.error_reply('ORDER_ARCHIVE_INDEX_CORRUPT') end
end
redis.call('XACK', KEYS[1], group, id)
redis.call('HDEL', KEYS[2], id)
return 1
