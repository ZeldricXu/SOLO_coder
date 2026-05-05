package com.paygateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PayGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayGatewayApplication.class, args);
    }
}
