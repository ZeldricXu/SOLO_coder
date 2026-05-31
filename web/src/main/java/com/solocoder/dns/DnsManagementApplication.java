package com.solocoder.dns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.solocoder.dns")
public class DnsManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(DnsManagementApplication.class, args);
    }
}
