package com.delivery.tracker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("com.delivery.tracker.mapper")
public class DeliveryTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryTrackerApplication.class, args);
    }
}
