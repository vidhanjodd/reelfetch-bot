package com.reelfetch_bot.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlCacheService {

    private static final String KEY_PREFIX = "rf:url:";

    private final StringRedisTemplate redis;

    @Value("${cache.url-mapping.ttl-hours:24}")
    private long ttlHours;

    public void put(String originalUrl, String r2Key) {
        String cacheKey = KEY_PREFIX + originalUrl;
        redis.opsForValue().set(cacheKey, r2Key, Duration.ofHours(ttlHours));
        log.debug("Cached URL → R2 key: {} → {}", originalUrl, r2Key);
    }

    public Optional<String> get(String originalUrl) {
        String value = redis.opsForValue().get(KEY_PREFIX + originalUrl);
        return Optional.ofNullable(value);
    }

    public void evict(String originalUrl) {
        redis.delete(KEY_PREFIX + originalUrl);
    }
}