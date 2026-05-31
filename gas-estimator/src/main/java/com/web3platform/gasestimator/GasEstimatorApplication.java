package com.web3platform.gasestimator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.web3platform.gasestimator", "com.web3platform.persistence", "com.web3platform.chaininteraction"})
@EnableConfigurationProperties
public class GasEstimatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GasEstimatorApplication.class, args);
    }
}
