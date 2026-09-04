package com.example.gateway;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> tokenBucketScript;

    // Bucket Configuration
    private static final long BUCKET_CAPACITY = 10;     // Max burst size
    private static final long REFILL_RATE_PER_SEC = 2;   // Replenish 2 tokens every second

    public record RateLimitResult(boolean allowed, long remaining, long limit, long retryAfterSeconds) {}

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        // Load the Lua script from resources
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(new ClassPathResource("token_bucket.lua"));
        this.tokenBucketScript.setResultType(List.class);
    }

    public RateLimitResult checkLimit(String clientKey) {
        String key = "rate_limit:" + clientKey;
        long now = Instant.now().toEpochMilli();

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(BUCKET_CAPACITY),
                String.valueOf(REFILL_RATE_PER_SEC),
                String.valueOf(now),
                "1" // 1 token per request
        );

        if (result == null || result.isEmpty()) {
            // Fail-open strategy if Redis call fails: allow request through
            return new RateLimitResult(true, 1, BUCKET_CAPACITY, 0);
        }

        boolean allowed = result.get(0) == 1L;
        long remaining = result.get(1);
        long retryAfter = result.get(2);

        return new RateLimitResult(allowed, remaining, BUCKET_CAPACITY, retryAfter);
    }
}