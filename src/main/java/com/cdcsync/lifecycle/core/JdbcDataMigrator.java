package com.cdcsync.lifecycle.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcDataMigrator extends AbstractDataMigrator {

    private final JdbcTemplate jdbcTemplate;

    @Override
    protected void doMigrate(String resourceId, StorageTier fromTier, StorageTier toTier) {
        log.info("Executing JDBC data migration: resourceId={}, from={}, to={}", resourceId, fromTier, toTier);

        String updateSql = "UPDATE cdc_change_event SET storage_tier = ?, updated_at = ? WHERE id = ?";
        int updated = jdbcTemplate.update(updateSql, toTier.name(), LocalDateTime.now(), resourceId);

        if (updated == 0) {
            throw new RuntimeException("Resource not found: " + resourceId);
        }

        log.info("JDBC data migration executed successfully: resourceId={}, rowsAffected={}", resourceId, updated);
    }

    @Override
    public boolean supports(String resourceType) {
        return "JDBC".equalsIgnoreCase(resourceType) || "CHANGE_EVENT".equalsIgnoreCase(resourceType);
    }
}
