package com.scheduler.persistence.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                log.info("Starting Flyway migration...");
                flyway.migrate();
                log.info("Flyway migration completed successfully");
            } catch (Exception e) {
                log.error("Flyway migration failed, attempting repair...", e);
                try {
                    flyway.repair();
                    flyway.migrate();
                    log.info("Flyway migration recovered successfully");
                } catch (Exception ex) {
                    log.error("Flyway migration failed after repair", ex);
                    throw ex;
                }
            }
        };
    }
}
