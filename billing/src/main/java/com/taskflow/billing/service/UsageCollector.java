package com.taskflow.billing.service;

import com.taskflow.billing.model.ResourceUsage;
import com.taskflow.common.utils.IdGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageCollector {

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> usageCounters = new ConcurrentHashMap<>();

    public void recordUsage(String tenantId, String resourceType, double amount, Map<String, String> tags) {
        String counterKey = tenantId + ":" + resourceType;

        Counter counter = usageCounters.computeIfAbsent(counterKey, k ->
                Counter.builder("resource.usage")
                        .description("Resource usage counter")
                        .tag("tenantId", tenantId)
                        .tag("resourceType", resourceType)
                        .register(meterRegistry)
        );

        counter.increment(amount);
        log.debug("Recorded usage: tenant={}, resource={}, amount={}", tenantId, resourceType, amount);
    }

    public void recordTaskExecution(String tenantId, String taskId, long durationMs) {
        recordUsage(tenantId, "task_executions", 1, Map.of("taskId", taskId));
        recordUsage(tenantId, "compute_minutes", durationMs / 60000.0, Map.of("taskId", taskId));
    }

    public void recordApiCall(String tenantId, String endpoint) {
        recordUsage(tenantId, "api_calls", 1, Map.of("endpoint", endpoint));
    }

    public void recordStorageUsage(String tenantId, long bytes) {
        recordUsage(tenantId, "storage_gb", bytes / (1024.0 * 1024 * 1024), Map.of());
    }

    public ResourceUsage createUsageRecord(String tenantId, String resourceType,
                                           BigDecimal amount, String unit,
                                           LocalDateTime periodStart, LocalDateTime periodEnd,
                                           Map<String, Object> dimensions) {
        return ResourceUsage.builder()
                .usageId(IdGenerator.generateId("usage"))
                .tenantId(tenantId)
                .resourceType(resourceType)
                .usageAmount(amount)
                .unit(unit)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .dimensions(dimensions)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public BigDecimal getCurrentUsage(String tenantId, String resourceType) {
        String counterKey = tenantId + ":" + resourceType;
        Counter counter = usageCounters.get(counterKey);
        if (counter == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(counter.count());
    }

    public List<ResourceUsage> getUsageForPeriod(String tenantId, LocalDateTime start, LocalDateTime end) {
        return List.of();
    }
}
