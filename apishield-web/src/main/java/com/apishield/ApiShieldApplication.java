package com.apishield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.apishield")
public class ApiShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiShieldApplication.class, args);
    }
}
