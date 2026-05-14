package com.schedulebook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.schedulebook.config.DispatchStrategyConfig;
import com.schedulebook.config.LockTimeoutConfig;
import com.schedulebook.config.ReminderIntervalConfig;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
    LockTimeoutConfig.class,
    ReminderIntervalConfig.class,
    DispatchStrategyConfig.class
})
public class ScheduleBookApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ScheduleBookApplication.class, args);
    }
}
