package com.apishield.infrastructure.config;

import com.apishield.infrastructure.cache.CacheService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
public class CacheConfig {

    @Bean
    public CacheService<String, Object> defaultCache() {
        return new CacheService<>(Duration.ofMinutes(10), 10000);
    }

    @Bean
    public CacheService<String, String> securityCache() {
        return new CacheService<>(Duration.ofMinutes(5), 5000);
    }
}
