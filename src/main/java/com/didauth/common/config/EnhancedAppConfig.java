package com.didauth.common.config;

import com.didauth.common.cache.CacheProperties;
import com.didauth.common.cache.MultiLevelCache;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
@Import({
        CacheConfig.class,
        RedisConfig.class,
        WebClientConfig.class,
        CorsConfig.class,
        MybatisMetaObjectHandler.class
})
public class EnhancedAppConfig {

    @Bean
    public MultiLevelCache multiLevelCache() {
        return new MultiLevelCache(
                org.springframework.cache.CacheManager.class.cast(null),
                org.springframework.data.redis.core.ReactiveRedisTemplate.class.cast(null)
        );
    }
}
