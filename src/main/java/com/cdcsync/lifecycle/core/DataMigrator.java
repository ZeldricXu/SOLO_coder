package com.cdcsync.lifecycle.core;

public interface DataMigrator {

    void migrate(String resourceId, StorageTier fromTier, StorageTier toTier);

    boolean supports(String resourceType);
}
