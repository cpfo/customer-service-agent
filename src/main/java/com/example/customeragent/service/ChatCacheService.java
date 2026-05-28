package com.example.customeragent.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ChatCacheService {

    private static final Logger log = LoggerFactory.getLogger(ChatCacheService.class);
    private static final String PREFIX = "chat:cache:";

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final long ttlMinutes;

    public ChatCacheService(StringRedisTemplate redisTemplate,
                            @Value("${app.cache.enabled:true}") boolean enabled,
                            @Value("${app.cache.ttl-minutes:60}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.ttlMinutes = Math.max(ttlMinutes, 1);
    }

    public String get(String sessionId, String message) {
        if (!enabled) return null;
        String key = cacheKey(sessionId, message);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("缓存命中: session={}, query=\"{}\"", truncate(sessionId, 20), truncate(message, 30));
            return cached;
        }
        return null;
    }

    public void put(String sessionId, String message, String reply) {
        if (!enabled || reply == null) return;
        String key = cacheKey(sessionId, message);
        redisTemplate.opsForValue().set(key, reply, ttlMinutes, TimeUnit.MINUTES);
        log.debug("缓存已保存: session={}, ttl={}m", truncate(sessionId, 20), ttlMinutes);
    }

    public void invalidate(String sessionId, String message) {
        String key = cacheKey(sessionId, message);
        redisTemplate.delete(key);
    }

    public long size() {
        Set<String> keys = redisTemplate.keys(PREFIX + "*");
        return keys == null ? 0 : keys.size();
    }

    private String cacheKey(String sessionId, String message) {
        String raw = sessionId + "|" + message;
        return PREFIX + DigestUtils.md5Hex(raw);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
