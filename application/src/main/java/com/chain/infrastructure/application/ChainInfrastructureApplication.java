package com.chain.infrastructure.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.chain.infrastructure")
public class ChainInfrastructureApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainInfrastructureApplication.class, args);
    }
}
