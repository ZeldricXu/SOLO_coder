package com.exam;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.exam.mapper")
@EnableAsync
@EnableScheduling
public class OnlineExamApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnlineExamApplication.class, args);
    }
}
