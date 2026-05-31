package com.monitoring.common.config;

import com.monitoring.common.event.DefaultEventPublisher;
import com.monitoring.common.event.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher() {
        return new DefaultEventPublisher();
    }
}
