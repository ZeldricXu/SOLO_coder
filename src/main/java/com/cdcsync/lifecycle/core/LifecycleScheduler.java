package com.cdcsync.lifecycle.core;

import com.cdcsync.lifecycle.domain.LifecyclePolicy;
import com.cdcsync.lifecycle.service.LifecyclePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleScheduler {

    private final LifecyclePolicyService lifecyclePolicyService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void runLifecyclePolicies() {
        log.info("Starting lifecycle policy execution at {}", LocalDateTime.now());

        List<LifecyclePolicy> policies = lifecyclePolicyService.findAll();
        int executedCount = 0;

        for (LifecyclePolicy policy : policies) {
            if (Boolean.TRUE.equals(policy.getEnabled())) {
                try {
                    log.info("Executing lifecycle policy: {} (id={})", policy.getName(), policy.getId());
                    lifecyclePolicyService.applyPolicy(policy.getId(), "auto-execution");
                    executedCount++;
                } catch (Exception e) {
                    log.error("Failed to execute lifecycle policy: {} (id={})", policy.getName(), policy.getId(), e);
                }
            }
        }

        log.info("Lifecycle policy execution completed at {}, executed {} policies", LocalDateTime.now(), executedCount);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeExpiredData() {
        log.info("Starting expired data purge at {}", LocalDateTime.now());

        List<LifecyclePolicy> policies = lifecyclePolicyService.findAll();
        int purgedCount = 0;

        for (LifecyclePolicy policy : policies) {
            if (Boolean.TRUE.equals(policy.getEnabled()) && policy.getDeleteAfterDays() != null) {
                try {
                    lifecyclePolicyService.purgeExpiredData(policy.getId());
                    purgedCount++;
                } catch (Exception e) {
                    log.error("Failed to purge expired data for policy: {} (id={})", policy.getName(), policy.getId(), e);
                }
            }
        }

        log.info("Expired data purge completed at {}, purged {} resources", LocalDateTime.now(), purgedCount);
    }
}
