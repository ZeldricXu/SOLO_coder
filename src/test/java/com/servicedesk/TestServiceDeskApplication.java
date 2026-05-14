package com.servicedesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestServiceDeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestServiceDeskApplication.class, args);
    }
}
