package com.memberscore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MemberScoreApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(MemberScoreApplication.class, args);
    }
}
