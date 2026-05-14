package com.medical.appointment.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public ExecutorService testExecutorService() {
        return Executors.newFixedThreadPool(2);
    }
}
