package com.enterprise.gateway.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        RedisSerializer<String> keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        RedisSerializationContext<String, Object> serializationContext = RedisSerializationContext
                .<String, Object>newSerializationContext()
                .key(keySerializer)
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }

    @Bean
    public RedisSerializer<String> redisKeySerializer() {
        return new StringRedisSerializer();
    }

    @Bean
    public GenericJackson2JsonRedisSerializer redisValueSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }

    @Bean
    public org.springframework.data.redis.core.script.DefaultRedisScript<Long> tokenBucketScript() {
        String script = """
                local key = KEYS[1]
                local capacity = tonumber(ARGV[1])
                local rate = tonumber(ARGV[2])
                local now = tonumber(ARGV[3])
                local requested = tonumber(ARGV[4])
                
                local last_tokens = tonumber(redis.call('hget', key, 'tokens') or capacity)
                local last_refreshed = tonumber(redis.call('hget', key, 'refreshed') or now)
                
                local delta = math.max(0, now - last_refreshed)
                local filled_tokens = math.min(capacity, last_tokens + delta * rate)
                local allowed = filled_tokens >= requested
                
                local new_tokens = filled_tokens
                if allowed then
                    new_tokens = filled_tokens - requested
                end
                
                redis.call('hset', key, 'tokens', new_tokens)
                redis.call('hset', key, 'refreshed', now)
                redis.call('expire', key, 3600)
                
                return allowed and 1 or 0
                """;

        org.springframework.data.redis.core.script.DefaultRedisScript<Long> redisScript = new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}
