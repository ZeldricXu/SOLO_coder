package com.cdcsync.lifecycle.core;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractDataMigrator implements DataMigrator {

    @Override
    public void migrate(String resourceId, StorageTier fromTier, StorageTier toTier) {
        log.info("Starting data migration: resourceId={}, from={}, to={}", resourceId, fromTier, toTier);
        try {
            preMigrate(resourceId, fromTier, toTier);
            doMigrate(resourceId, fromTier, toTier);
            postMigrate(resourceId, fromTier, toTier);
            log.info("Data migration completed: resourceId={}, from={}, to={}", resourceId, fromTier, toTier);
        } catch (Exception e) {
            log.error("Data migration failed: resourceId={}, from={}, to={}", resourceId, fromTier, toTier, e);
            throw new RuntimeException("Data migration failed", e);
        }
    }

    protected void preMigrate(String resourceId, StorageTier fromTier, StorageTier toTier) {
    }

    protected abstract void doMigrate(String resourceId, StorageTier fromTier, StorageTier toTier);

    protected void postMigrate(String resourceId, StorageTier fromTier, StorageTier toTier) {
    }
}
