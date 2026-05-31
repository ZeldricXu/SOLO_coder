package com.edgescheduler;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("com.edgescheduler.infrastructure.mapper")
public class EdgeSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeSchedulerApplication.class, args);
    }
}
