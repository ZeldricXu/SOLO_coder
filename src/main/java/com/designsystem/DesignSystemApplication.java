package com.designsystem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.designsystem.mapper")
public class DesignSystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(DesignSystemApplication.class, args);
    }
}
