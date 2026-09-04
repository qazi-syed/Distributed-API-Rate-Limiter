package com.example.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class ProxyController {

    private final RateLimiterService rateLimiterService;
    private final RestClient restClient;

    public ProxyController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
        this.restClient = RestClient.create("http://localhost:8081");
    }

    @GetMapping("/api/**")
    public ResponseEntity<String> proxy(
            @RequestHeader(value = "X-API-Key", defaultValue = "guest") String apiKey,
            HttpServletRequest request) {

        // 1. Check token availability in bucket
        RateLimiterService.RateLimitResult result = rateLimiterService.checkLimit(apiKey);

        // 2. Reject if no tokens remain
        if (!result.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-RateLimit-Limit", String.valueOf(result.limit()))
                    .header("X-RateLimit-Remaining", "0")
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()))
                    .body("{\"error\": \"Too Many Requests\", \"retry_after_seconds\": " + result.retryAfterSeconds() + "}");
        }

        // 3. Strip '/api' prefix and forward to mock backend
        String targetPath = request.getRequestURI().replaceFirst("^/api", "");
        if (targetPath.isEmpty()) targetPath = "/";

        String upstreamResponse = restClient.get()
                .uri(targetPath)
                .retrieve()
                .body(String.class);

        // 4. Return successful response with remaining token count
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-RateLimit-Limit", String.valueOf(result.limit()))
                .header("X-RateLimit-Remaining", String.valueOf(result.remaining()))
                .body(upstreamResponse);
    }
}