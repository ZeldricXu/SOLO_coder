package com.modelguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ModelGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelGuardApplication.class, args);
    }
}
