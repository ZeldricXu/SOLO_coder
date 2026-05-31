package com.apishield.shamir.infrastructure.config;

import com.apishield.shamir.domain.service.ShamirCryptoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShamirConfig {

    @Bean
    public ShamirCryptoService shamirCryptoService() {
        return new ShamirCryptoService();
    }
}
