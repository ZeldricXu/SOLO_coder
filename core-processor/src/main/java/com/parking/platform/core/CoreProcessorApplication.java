package com.parking.platform.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CoreProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreProcessorApplication.class, args);
    }
}
