package com.exam.config;

import com.exam.service.sampler.WeightedReservoirSampler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;

@Configuration
public class SamplerConfig {

    @Bean
    public WeightedReservoirSampler weightedReservoirSampler() {
        return new WeightedReservoirSampler(new Random());
    }
}
