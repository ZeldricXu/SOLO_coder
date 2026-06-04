package com.battle.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BattlePlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(BattlePlatformApplication.class, args);
    }
}
