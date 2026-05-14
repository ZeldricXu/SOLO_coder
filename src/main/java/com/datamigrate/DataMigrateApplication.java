package com.datamigrate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DataMigrateApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataMigrateApplication.class, args);
    }
}
