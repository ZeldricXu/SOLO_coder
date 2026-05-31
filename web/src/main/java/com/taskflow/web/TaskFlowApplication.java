package com.taskflow.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.taskflow")
@MapperScan("com.taskflow.data.mapper")
@EnableScheduling
@EnableAsync
public class TaskFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskFlowApplication.class, args);
        System.out.println("""
                ========================================
                  TaskFlow Platform Started Successfully!
                  Version: 1.0.0
                  Access: http://localhost:8080
                ========================================
                """);
    }
}
