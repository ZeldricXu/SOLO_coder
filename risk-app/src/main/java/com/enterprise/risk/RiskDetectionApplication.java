package com.enterprise.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.enterprise.risk.common",
        "com.enterprise.risk.gateway",
        "com.enterprise.risk.engine",
        "com.enterprise.risk.model",
        "com.enterprise.risk.alert",
        "com.enterprise.risk.orchestration",
        "com.enterprise.risk.observability",
        "com.enterprise.risk.storage"
})
@EntityScan(basePackages = {
        "com.enterprise.risk.common",
        "com.enterprise.risk.storage.entity"
})
@EnableJpaRepositories(basePackages = "com.enterprise.risk.storage.repository")
@EnableJpaAuditing
@EnableKafka
@EnableAsync
@EnableScheduling
public class RiskDetectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskDetectionApplication.class, args);
    }
}
