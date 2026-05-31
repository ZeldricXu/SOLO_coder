package com.parking.platform.environment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EnvironmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(EnvironmentApplication.class, args);
    }
}
