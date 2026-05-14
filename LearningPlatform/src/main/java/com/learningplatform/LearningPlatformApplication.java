
package com.learningplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
@EnableAsync
@EnableCaching
public class LearningPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearningPlatformApplication.class, args);
    }
}
