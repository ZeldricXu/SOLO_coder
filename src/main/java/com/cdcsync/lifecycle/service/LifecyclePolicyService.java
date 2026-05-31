package com.cdcsync.lifecycle.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.lifecycle.domain.LifecyclePolicy;

public interface LifecyclePolicyService extends BaseService<LifecyclePolicy, String> {

    void applyPolicy(String policyId, String resourceId);

    void migrateToWarmStorage(String resourceId);

    void migrateToColdStorage(String resourceId);

    void archiveData(String resourceId);

    void purgeExpiredData(String resourceId);
}
