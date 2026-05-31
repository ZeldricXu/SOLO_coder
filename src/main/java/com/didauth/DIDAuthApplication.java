package com.didauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DIDAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(DIDAuthApplication.class, args);
    }
}
