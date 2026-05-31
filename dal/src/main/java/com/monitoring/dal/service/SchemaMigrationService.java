package com.monitoring.dal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaMigrationService {

    private final DataSource dataSource;

    public void migrate() {
        log.info("Starting database schema migration...");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .table("schema_version")
                .load();
        flyway.repair();
        flyway.migrate();
        log.info("Database schema migration completed successfully");
    }

    public String getCurrentVersion() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .load();
        return flyway.info().current().getVersion().toString();
    }

    public boolean validate() {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .load();
            flyway.validate();
            return true;
        } catch (Exception e) {
            log.error("Schema validation failed", e);
            return false;
        }
    }
}
