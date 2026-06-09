package com.cardgame.rank.config;

import com.cardgame.common.config.RedisConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Configuration
public class RedisRankConfig {

    @Autowired
    private RedisConfig redisConfig;

    @Bean
    public JedisPool jedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(redisConfig.getMaxTotal());
        poolConfig.setMaxIdle(redisConfig.getMaxIdle());
        poolConfig.setMinIdle(redisConfig.getMinIdle());

        if (redisConfig.getPassword() != null && !redisConfig.getPassword().isEmpty()) {
            return new JedisPool(poolConfig,
                    redisConfig.getHost(),
                    redisConfig.getPort(),
                    redisConfig.getTimeout(),
                    redisConfig.getPassword(),
                    redisConfig.getDatabase());
        } else {
            return new JedisPool(poolConfig,
                    redisConfig.getHost(),
                    redisConfig.getPort(),
                    redisConfig.getTimeout(),
                    null,
                    redisConfig.getDatabase());
        }
    }
}
