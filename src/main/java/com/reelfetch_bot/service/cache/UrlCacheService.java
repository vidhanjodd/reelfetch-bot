package com.reelfetch_bot.service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlCacheService {

    private static final String KEY_PREFIX = "rf:url:";

    private final StringRedisTemplate redis;

    @Value("${cache.url-mapping.ttl-hours:24}")
    private long ttlHours;

    public void put(String originalUrl, String r2Key) {
        String key = cacheKey(originalUrl);
        redis.opsForValue().set(key, r2Key, Duration.ofHours(ttlHours));
        log.debug("Cached URL → R2 key: {} → {}", key, r2Key);
    }

    public Optional<String> get(String originalUrl) {
        String key = cacheKey(originalUrl);
        String value = redis.opsForValue().get(key);
        log.debug("Cache lookup: {} → {}", key, value != null ? "HIT" : "MISS");
        return Optional.ofNullable(value);
    }

    public void evict(String originalUrl) {
        String key = cacheKey(originalUrl);
        redis.delete(key);
        log.debug("Evicted cache key: {}", key);
    }

    public void clearAll() {
        Set<String> keys = redis.keys(KEY_PREFIX + "*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
            log.info("Cleared {} cache entries", keys.size());
        }
    }

    private String cacheKey(String url) {
        String normalized = url.trim().toLowerCase();
        int q = normalized.indexOf('?');
        if (q > 0) normalized = normalized.substring(0, q);
        int h = normalized.indexOf('#');
        if (h > 0) normalized = normalized.substring(0, h);
        normalized = normalized.replace("://www.instagram.com/", "://instagram.com/");
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return KEY_PREFIX + normalized;
    }
}