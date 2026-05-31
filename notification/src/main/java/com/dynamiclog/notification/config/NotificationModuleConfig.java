package com.dynamiclog.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ComponentScan("com.dynamiclog.notification")
@EnableScheduling
public class NotificationModuleConfig {

    @Bean
    public WebClient.Builder notificationWebClientBuilder() {
        return WebClient.builder();
    }
}
