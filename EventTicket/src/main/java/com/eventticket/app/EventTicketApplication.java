package com.eventticket.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(scanBasePackages = "com.eventticket")
@EntityScan(basePackages = "com.eventticket.entity")
@EnableJpaRepositories(basePackages = "com.eventticket.repository")
@EnableTransactionManagement
public class EventTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventTicketApplication.class, args);
    }
}
