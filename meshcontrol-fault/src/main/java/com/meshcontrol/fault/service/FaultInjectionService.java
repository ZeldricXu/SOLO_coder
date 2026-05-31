package com.meshcontrol.fault.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.fault.dto.FaultInjectRequest;
import com.meshcontrol.fault.dto.FaultScenarioRequest;
import com.meshcontrol.fault.entity.FaultInjection;
import com.meshcontrol.fault.entity.FaultScenario;
import com.meshcontrol.fault.mapper.FaultInjectionMapper;
import com.meshcontrol.fault.mapper.FaultScenarioMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaultInjectionService extends BaseService<FaultScenarioMapper, FaultScenario> {

    private final FaultScenarioMapper faultScenarioMapper;
    private final FaultInjectionMapper faultInjectionMapper;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledRollbacks = new java.util.concurrent.ConcurrentHashMap<>();

    @Transactional
    public FaultScenario createScenario(FaultScenarioRequest request) {
        FaultScenario scenario = new FaultScenario();
        scenario.setScenarioId(IdGenerator.generateId("fs"));
        scenario.setName(request.getName());
        scenario.setDescription(request.getDescription());
        scenario.setFaultType(request.getFaultType());
        scenario.setTargetSelector(request.getTargetSelector());
        scenario.setInjectionConfig(request.getInjectionConfig() != null ? request.getInjectionConfig() : getDefaultConfig(request.getFaultType()));
        scenario.setDurationSeconds(request.getDurationSeconds());
        scenario.setAutoRollback(request.getAutoRollback());
        scenario.setRollbackConfig(request.getRollbackConfig());
        scenario.setEnabled(request.getEnabled());
        scenario.setStatus("created");

        faultScenarioMapper.insert(scenario);
        log.info("Fault scenario created: {} type: {}", scenario.getScenarioId(), scenario.getFaultType());
        return scenario;
    }

    public IPage<FaultScenario> listScenarios(String faultType, Boolean enabled, int pageNum, int pageSize) {
        LambdaQueryWrapper<FaultScenario> wrapper = new LambdaQueryWrapper<>();
        if (faultType != null) {
            wrapper.eq(FaultScenario::getFaultType, faultType);
        }
        if (enabled != null) {
            wrapper.eq(FaultScenario::getEnabled, enabled);
        }
        wrapper.orderByDesc(FaultScenario::getCreatedAt);
        return page(pageNum, pageSize, wrapper);
    }

    public FaultScenario getScenario(String scenarioId) {
        return faultScenarioMapper.selectById(scenarioId);
    }

    @Transactional
    public boolean deleteScenario(String scenarioId) {
        return faultScenarioMapper.deleteById(scenarioId) > 0;
    }

    @Transactional
    public FaultInjection injectFault(FaultInjectRequest request) {
        FaultScenario scenario = faultScenarioMapper.selectById(request.getScenarioId());
        if (scenario == null) {
            throw new BusinessException("Fault scenario not found");
        }

        if (!scenario.getEnabled()) {
            throw new BusinessException("Fault scenario is not enabled");
        }

        List<String> targets = request.getTargets() != null ? request.getTargets() : resolveTargets(scenario);
        if (targets.isEmpty()) {
            throw new BusinessException("No targets found for fault injection");
        }

        FaultInjection injection = new FaultInjection();
        injection.setInjectionId(IdGenerator.generateId("fij"));
        injection.setScenarioId(request.getScenarioId());
        injection.setTargets(targets);
        injection.setStatus("injecting");
        injection.setStartedAt(LocalDateTime.now());

        faultInjectionMapper.insert(injection);
        log.info("Fault injection started: {} scenario: {} targets: {}",
                injection.getInjectionId(), request.getScenarioId(), targets.size());

        performInjection(scenario, targets);
        injection.setStatus("active");
        faultInjectionMapper.updateById(injection);

        Integer duration = request.getDurationSeconds() != null ? request.getDurationSeconds() : scenario.getDurationSeconds();
        if (duration != null && duration > 0) {
            scheduleAutoRollback(injection, duration);
        }

        return injection;
    }

    @Transactional
    public boolean rollbackInjection(String injectionId) {
        FaultInjection injection = faultInjectionMapper.selectById(injectionId);
        if (injection == null) {
            throw new BusinessException("Fault injection not found");
        }

        if ("rollback_completed".equals(injection.getStatus()) || "rollback_failed".equals(injection.getStatus())) {
            return true;
        }

        injection.setStatus("rolling_back");
        injection.setRollbackStartedAt(LocalDateTime.now());
        faultInjectionMapper.updateById(injection);

        try {
            FaultScenario scenario = faultScenarioMapper.selectById(injection.getScenarioId());
            performRollback(scenario, injection.getTargets());
            injection.setStatus("rollback_completed");
            injection.setRollbackCompletedAt(LocalDateTime.now());
            log.info("Fault injection rolled back: {}", injectionId);
        } catch (Exception e) {
            injection.setStatus("rollback_failed");
            injection.setErrorDetail(e.getMessage());
            log.error("Failed to rollback fault injection: {}", injectionId, e);
        }

        ScheduledFuture<?> future = scheduledRollbacks.remove(injectionId);
        if (future != null) {
            future.cancel(false);
        }

        faultInjectionMapper.updateById(injection);
        return "rollback_completed".equals(injection.getStatus());
    }

    public List<FaultInjection> listInjections(String scenarioId, String status) {
        if (scenarioId != null) {
            return faultInjectionMapper.findByScenarioId(scenarioId);
        }
        if (status != null) {
            return faultInjectionMapper.findByStatus(status);
        }
        return faultInjectionMapper.selectList(null);
    }

    public FaultInjection getInjection(String injectionId) {
        return faultInjectionMapper.selectById(injectionId);
    }

    private Map<String, Object> getDefaultConfig(String faultType) {
        Map<String, Object> config = new java.util.HashMap<>();
        switch (faultType) {
            case "delay":
                config.put("delayMs", 1000);
                config.put("percentage", 100);
                break;
            case "abort":
                config.put("errorCode", 500);
                config.put("percentage", 100);
                break;
            case "cpu_stress":
                config.put("loadPercent", 80);
                break;
            case "memory_stress":
                config.put("percentUsed", 70);
                break;
            default:
                config.put("percentage", 100);
        }
        return config;
    }

    private List<String> resolveTargets(FaultScenario scenario) {
        Map<String, Object> selector = scenario.getTargetSelector();
        if (selector == null || selector.isEmpty()) {
            return List.of("target-1", "target-2");
        }
        return List.of("dynamic-target-1", "dynamic-target-2");
    }

    private void performInjection(FaultScenario scenario, List<String> targets) {
        for (String target : targets) {
            log.info("Injecting fault type {} to target: {}", scenario.getFaultType(), target);
        }
    }

    private void performRollback(FaultScenario scenario, List<String> targets) {
        for (String target : targets) {
            log.info("Rolling back fault type {} from target: {}", scenario.getFaultType(), target);
        }
    }

    private void scheduleAutoRollback(FaultInjection injection, int durationSeconds) {
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> rollbackInjection(injection.getInjectionId()),
                LocalDateTime.now().plusSeconds(durationSeconds)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant());
        scheduledRollbacks.put(injection.getInjectionId(), future);
    }

    public Map<String, Object> getInjectionStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("activeInjections", faultInjectionMapper.findByStatus("active").size());
        stats.put("totalScenarios", faultScenarioMapper.selectCount(null));
        stats.put("activeScenarios", faultScenarioMapper.findActive().size());
        stats.put("scheduledRollbacks", scheduledRollbacks.size());
        return stats;
    }
}
