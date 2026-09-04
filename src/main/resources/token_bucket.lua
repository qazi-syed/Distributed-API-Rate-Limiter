-- KEYS[1]: User/Client Redis key (e.g. rate_limit:user123)
-- ARGV[1]: Bucket capacity (maximum tokens allowed, e.g. 10)
-- ARGV[2]: Refill rate in tokens per second (e.g. 2)
-- ARGV[3]: Current timestamp in milliseconds
-- ARGV[4]: Tokens requested (typically 1)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- 1. Fetch current token balance and the last refill timestamp
local data = redis.call('HMGET', key, 'tokens', 'last_refreshed')
local tokens = tonumber(data[1])
local last_refreshed = tonumber(data[2])

-- 2. Initialize bucket if this is the client's first visit
if tokens == nil then
    tokens = capacity
    last_refreshed = now
else
    -- Lazy Refill: calculate tokens generated since the last request
    local elapsed_seconds = math.max(0, (now - last_refreshed) / 1000.0)
    tokens = math.min(capacity, tokens + (elapsed_seconds * refill_rate))
    last_refreshed = now
end

-- 3. Consume token or reject
if tokens >= requested then
    tokens = tokens - requested
    redis.call('HSET', key, 'tokens', tokens, 'last_refreshed', last_refreshed)
    
    -- Expire idle keys automatically once bucket would be fully refilled
    local ttl = math.ceil(capacity / refill_rate) * 2
    redis.call('EXPIRE', key, ttl)
    
    return {1, math.floor(tokens), 0} -- {Allowed: true, Remaining, Retry-After: 0}
else
    redis.call('HSET', key, 'tokens', tokens, 'last_refreshed', last_refreshed)
    
    -- Calculate seconds until at least 1 token is refilled
    local needed = requested - tokens
    local retry_after = math.ceil(needed / refill_rate)
    
    return {0, math.floor(tokens), retry_after} -- {Allowed: false, Remaining, Retry-After}
end