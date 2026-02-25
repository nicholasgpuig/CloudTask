package com.cloudtask.jobservice.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.util.UUID;
import java.util.Optional;

@Service
public class IdempotencyService {
    
    private final RedisTemplate<String, String> redisTemplate;

    public IdempotencyService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String get(String userId, String idempotencyKey) {
        return redisTemplate.opsForValue().get("idempotency:" + userId + ":" + idempotencyKey);
    }

    public Boolean reserve(String userId, String idempotencyKey) {
        return redisTemplate.opsForValue().setIfAbsent("idempotency:" + userId + ":" + idempotencyKey, "PROCESSING", Duration.ofSeconds(30));
    }

    public void store(String userId, String idempotencyKey, String jsonResponse) {
        redisTemplate.opsForValue().set("idempotency:" + userId + ":" + idempotencyKey, jsonResponse, Duration.ofHours(24));
    }

    public Optional<String> extractKey(HttpServletRequest request) {
        String header = request.getHeader("Idempotency-Key");

        if (header == null || header.isBlank()) {
            return Optional.empty();
        }

        try {
            UUID.fromString(header);
            return Optional.of(header);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}