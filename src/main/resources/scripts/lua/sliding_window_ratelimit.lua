-- Sliding Window Rate Limiter Lua Script
-- KEYS[1]: rate limit key
-- ARGV[1]: window size in milliseconds
-- ARGV[2]: max requests in window
-- ARGV[3]: current timestamp in milliseconds

local key = KEYS[1]
local window_ms = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local cutoff = now - window_ms

-- Remove old entries outside the window
redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)

-- Count current requests in window
local current_count = redis.call('ZCARD', key)

if current_count < max_requests then
    -- Add current request with timestamp as both score and member
    redis.call('ZADD', key, now, now .. ':' .. math.random())
    -- Set expiry to clean up old keys
    redis.call('EXPIRE', key, math.ceil(window_ms / 1000) + 1)
    return 1
else
    return 0
end