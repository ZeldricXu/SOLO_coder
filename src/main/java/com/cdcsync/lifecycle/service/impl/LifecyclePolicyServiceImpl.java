package com.cdcsync.lifecycle.service.impl;

import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.lifecycle.core.ArchiveManager;
import com.cdcsync.lifecycle.core.DataMigrator;
import com.cdcsync.lifecycle.core.StorageTier;
import com.cdcsync.lifecycle.domain.LifecyclePolicy;
import com.cdcsync.lifecycle.mapper.LifecyclePolicyMapper;
import com.cdcsync.lifecycle.service.LifecyclePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LifecyclePolicyServiceImpl
        extends AbstractBaseService<LifecyclePolicy, String, LifecyclePolicyMapper>
        implements LifecyclePolicyService {

    private final List<DataMigrator> dataMigrators;
    private final ArchiveManager archiveManager;

    public LifecyclePolicyServiceImpl(LifecyclePolicyMapper mapper, List<DataMigrator> dataMigrators, ArchiveManager archiveManager) {
        super(mapper);
        this.dataMigrators = dataMigrators;
        this.archiveManager = archiveManager;
    }

    @Override
    protected void setId(LifecyclePolicy entity, String id) {
    }

    @Override
    protected String getId(LifecyclePolicy entity) {
        return entity.getId();
    }

    @Override
    public void applyPolicy(String policyId, String resourceId) {
        LifecyclePolicy policy = findById(policyId);
        if (policy == null) {
            throw new RuntimeException("Lifecycle policy not found: " + policyId);
        }

        if (!Boolean.TRUE.equals(policy.getEnabled())) {
            log.warn("Lifecycle policy is disabled: {}", policyId);
            return;
        }

        log.info("Applying lifecycle policy: {} to resource: {}", policy.getName(), resourceId);

        long resourceAgeDays = calculateResourceAge(resourceId);

        if (policy.getArchiveAfterDays() != null && resourceAgeDays >= policy.getArchiveAfterDays()) {
            archiveData(resourceId);
        } else if (policy.getColdStorageDays() != null && resourceAgeDays >= policy.getColdStorageDays()) {
            migrateToColdStorage(resourceId);
        } else if (policy.getWarmStorageDays() != null && resourceAgeDays >= policy.getWarmStorageDays()) {
            migrateToWarmStorage(resourceId);
        }

        log.info("Lifecycle policy applied successfully: {} to resource: {}", policy.getName(), resourceId);
    }

    @Override
    public void migrateToWarmStorage(String resourceId) {
        log.info("Migrating resource to warm storage: {}", resourceId);
        migrate(resourceId, StorageTier.HOT, StorageTier.WARM);
    }

    @Override
    public void migrateToColdStorage(String resourceId) {
        log.info("Migrating resource to cold storage: {}", resourceId);
        migrate(resourceId, StorageTier.WARM, StorageTier.COLD);
    }

    @Override
    public void archiveData(String resourceId) {
        log.info("Archiving resource: {}", resourceId);
        migrate(resourceId, StorageTier.COLD, StorageTier.ARCHIVED);
        archiveManager.archive(resourceId, "archived-data-" + resourceId);
    }

    @Override
    public void purgeExpiredData(String resourceId) {
        log.info("Purging expired data: {}", resourceId);

        LifecyclePolicy policy = findById(resourceId);
        if (policy != null && policy.getDeleteAfterDays() != null) {
            long resourceAgeDays = calculateResourceAge(resourceId);
            if (resourceAgeDays >= policy.getDeleteAfterDays()) {
                log.info("Deleting expired resource: {}", resourceId);
                archiveManager.deleteArchive(resourceId);
            }
        }
    }

    private void migrate(String resourceId, StorageTier fromTier, StorageTier toTier) {
        for (DataMigrator migrator : dataMigrators) {
            if (migrator.supports("CHANGE_EVENT")) {
                migrator.migrate(resourceId, fromTier, toTier);
                return;
            }
        }
        throw new RuntimeException("No suitable DataMigrator found for resource type: CHANGE_EVENT");
    }

    private long calculateResourceAge(String resourceId) {
        return java.time.Duration.between(LocalDateTime.now().minusDays(1), LocalDateTime.now()).toDays();
    }
}
