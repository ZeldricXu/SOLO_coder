package com.chaoslab.modules.faultinject.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.entity.FaultInjectionRun;
import com.chaoslab.entity.FaultScenario;
import com.chaoslab.event.DomainEvent;
import com.chaoslab.event.EventPublisher;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.FaultInjectionRunMapper;
import com.chaoslab.mapper.FaultScenarioMapper;
import com.chaoslab.modules.faultinject.dto.FaultInjectRequest;
import com.chaoslab.modules.faultinject.dto.FaultInjectionStatusResponse;
import com.chaoslab.modules.faultinject.dto.FaultScenarioCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class FaultInjectionService {

    private final FaultScenarioMapper scenarioMapper;
    private final FaultInjectionRunMapper runMapper;
    private final EventPublisher eventPublisher;

    private final Map<String, FaultInjectionRun> activeRuns = new ConcurrentHashMap<>();

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<FaultScenario> createScenario(FaultScenarioCreateRequest request) {
        return Mono.fromCallable(() -> {
            validateFaultType(request.getFaultType());
            validateScope(request.getScope());
            validateFaultConfig(request.getFaultType(), request.getConfig());

            FaultScenario scenario = new FaultScenario();
            scenario.setScenarioId("fs-" + UUID.randomUUID().toString().substring(0, 8));
            scenario.setName(request.getName());
            scenario.setDescription(request.getDescription());
            scenario.setFaultType(request.getFaultType());
            scenario.setScope(request.getScope());
            scenario.setConfig(request.getConfig());
            scenario.setDurationMs(request.getDurationMs());
            scenario.setAutoRollback(request.getAutoRollback());
            scenario.setRollbackTimeoutMs(request.getRollbackTimeoutMs());
            scenario.setTags(request.getTags());
            scenario.setEnabled(request.getEnabled());

            scenarioMapper.insert(scenario);
            log.info("Created fault scenario: {} type: {}", scenario.getScenarioId(), request.getFaultType());

            eventPublisher.publish("fault.scenario.created",
                    scenario.getScenarioId(), "fault_scenario", scenario).subscribe();

            return scenario;
        });
    }

    public Mono<List<FaultScenario>> listScenarios(String faultType, Boolean enabled) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FaultScenario> wrapper = new LambdaQueryWrapper<>();
            if (faultType != null && !faultType.isEmpty()) {
                wrapper.eq(FaultScenario::getFaultType, faultType);
            }
            if (enabled != null) {
                wrapper.eq(FaultScenario::getEnabled, enabled);
            }
            wrapper.orderByDesc(FaultScenario::getCreatedAt);
            return scenarioMapper.selectList(wrapper);
        });
    }

    public Mono<FaultScenario> getScenario(String scenarioId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FaultScenario> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FaultScenario::getScenarioId, scenarioId);
            FaultScenario scenario = scenarioMapper.selectOne(wrapper);
            if (scenario == null) {
                throw BusinessException.notFound("故障场景不存在: " + scenarioId);
            }
            return scenario;
        });
    }

    @Transactional
    public Mono<FaultInjectionRun> startInjection(FaultInjectRequest request) {
        return getScenario(request.getScenarioId())
                .flatMap(scenario -> {
                    if (!scenario.getEnabled()) {
                        return Mono.error(BusinessException.validationError("故障场景未启用"));
                    }

                    if (isScenarioRunning(request.getScenarioId())) {
                        return Mono.error(BusinessException.conflict("该场景已有正在运行的注入任务"));
                    }

                    return Mono.fromCallable(() -> {
                        List<String> targets = request.getTargetOverrides() != null && !request.getTargetOverrides().isEmpty()
                                ? request.getTargetOverrides()
                                : resolveTargets(scenario.getScope());

                        FaultInjectionRun run = new FaultInjectionRun();
                        run.setRunId("fir-" + UUID.randomUUID().toString().substring(0, 8));
                        run.setScenarioId(request.getScenarioId());
                        run.setStatus("running");
                        run.setPhase("preparing");
                        run.setTargets(targets);
                        run.setStartedAt(LocalDateTime.now());
                        run.setRollbackTriggered(false);

                        Map<String, Object> metrics = new HashMap<>();
                        metrics.put("targetCount", targets.size());
                        metrics.put("errorRateThreshold", request.getErrorRateThreshold());
                        metrics.put("latencyP99Threshold", request.getLatencyP99Threshold());
                        metrics.put("dryRun", request.getDryRun());
                        run.setMetrics(metrics);

                        runMapper.insert(run);
                        activeRuns.put(run.getRunId(), run);

                        if (!request.getDryRun()) {
                            executeFaultInjectionAsync(run, scenario);
                        }

                        eventPublisher.publish("fault.injection.started",
                                run.getRunId(), "fault_injection", run).subscribe();

                        log.info("Started fault injection: {} for scenario: {} targets: {}",
                                run.getRunId(), request.getScenarioId(), targets.size());
                        return run;
                    });
                });
    }

    @Transactional
    public Mono<FaultInjectionRun> triggerRollback(String runId, String reason) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FaultInjectionRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FaultInjectionRun::getRunId, runId);
            FaultInjectionRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("注入任务不存在: " + runId);
            }

            if (run.getRollbackTriggered()) {
                throw BusinessException.validationError("回滚已触发");
            }

            run.setRollbackTriggered(true);
            run.setRollbackReason(reason);
            run.setPhase("rollback_in_progress");
            runMapper.updateById(run);
            activeRuns.put(runId, run);

            executeRollbackAsync(run);

            eventPublisher.publish("fault.injection.rollback",
                    runId, "fault_injection", run).subscribe();

            log.warn("Triggered rollback for run: {} reason: {}", runId, reason);
            return run;
        });
    }

    @Transactional
    public Mono<FaultInjectionRun> stopInjection(String runId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FaultInjectionRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FaultInjectionRun::getRunId, runId);
            FaultInjectionRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("注入任务不存在: " + runId);
            }

            if ("completed".equals(run.getStatus()) || "stopped".equals(run.getStatus())) {
                throw BusinessException.validationError("注入任务已结束");
            }

            run.setStatus("stopped");
            run.setPhase("stopped");
            run.setEndedAt(LocalDateTime.now());
            runMapper.updateById(run);
            activeRuns.remove(runId);

            executeRollbackAsync(run);

            eventPublisher.publish("fault.injection.stopped",
                    runId, "fault_injection", run).subscribe();

            log.info("Stopped fault injection: {}", runId);
            return run;
        });
    }

    public Mono<FaultInjectionStatusResponse> getInjectionStatus(String runId) {
        return getInjectionRun(runId)
                .zipWith(getScenario(runMapper.selectOne(
                                new LambdaQueryWrapper<FaultInjectionRun>()
                                        .eq(FaultInjectionRun::getRunId, runId))
                        .getScenarioId()))
                .map(tuple -> {
                    FaultInjectionRun run = tuple.getT1();
                    FaultScenario scenario = tuple.getT2();

                    FaultInjectionStatusResponse response = new FaultInjectionStatusResponse();
                    response.setRunId(run.getRunId());
                    response.setScenarioId(run.getScenarioId());
                    response.setScenarioName(scenario.getName());
                    response.setFaultType(scenario.getFaultType());
                    response.setStatus(run.getStatus());
                    response.setPhase(run.getPhase());
                    response.setTargets(run.getTargets());
                    response.setStartedAt(run.getStartedAt());
                    response.setEndedAt(run.getEndedAt());
                    response.setRollbackTriggered(run.getRollbackTriggered());
                    response.setRollbackReason(run.getRollbackReason());
                    response.setRollbackCompletedAt(run.getRollbackCompletedAt());
                    response.setMetrics(run.getMetrics());
                    response.setFaultConfig(scenario.getConfig());

                    return response;
                });
    }

    private Mono<FaultInjectionRun> getInjectionRun(String runId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FaultInjectionRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FaultInjectionRun::getRunId, runId);
            FaultInjectionRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("注入任务不存在: " + runId);
            }
            return run;
        });
    }

    public Mono<List<FaultInjectionRun>> listActiveRuns() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<FaultInjectionRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FaultInjectionRun::getStatus, "running")
                    .orderByDesc(FaultInjectionRun::getStartedAt);
            return runMapper.selectList(wrapper);
        });
    }

    @Async
    @Transactional
    public void executeFaultInjectionAsync(FaultInjectionRun run, FaultScenario scenario) {
        try {
            run.setPhase("injecting");
            runMapper.updateById(run);
            activeRuns.put(run.getRunId(), run);

            log.info("Executing fault injection: {} type: {}", run.getRunId(), scenario.getFaultType());

            for (String target : run.getTargets()) {
                injectFault(target, scenario);
                Thread.sleep(100);
            }

            if (scenario.getDurationMs() != null && scenario.getDurationMs() > 0) {
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < scenario.getDurationMs()) {
                    if (run.getRollbackTriggered()) {
                        log.info("Rollback triggered, stopping injection for: {}", run.getRunId());
                        break;
                    }
                    if (!activeRuns.containsKey(run.getRunId())) {
                        log.info("Run stopped, stopping injection for: {}", run.getRunId());
                        break;
                    }
                    checkHealthAndAutoRollback(run, scenario);
                    Thread.sleep(1000);
                }
            }

            if (!run.getRollbackTriggered() && scenario.getAutoRollback()) {
                executeRollback(run);
            }

            if (!run.getRollbackTriggered()) {
                run.setStatus("completed");
                run.setPhase("completed");
                run.setEndedAt(LocalDateTime.now());
                runMapper.updateById(run);
                activeRuns.remove(run.getRunId());

                eventPublisher.publish("fault.injection.completed",
                        run.getRunId(), "fault_injection", run).subscribe();
            }

            log.info("Completed fault injection: {}", run.getRunId());
        } catch (Exception e) {
            log.error("Fault injection failed: {}", run.getRunId(), e);
            run.setStatus("failed");
            run.setErrorDetail(e.getMessage());
            run.setEndedAt(LocalDateTime.now());
            runMapper.updateById(run);
            activeRuns.remove(run.getRunId());

            if (scenario.getAutoRollback()) {
                executeRollback(run);
            }

            eventPublisher.publish("fault.injection.failed",
                    run.getRunId(), "fault_injection", run).subscribe();
        }
    }

    @Async
    @Transactional
    public void executeRollbackAsync(FaultInjectionRun run) {
        executeRollback(run);
    }

    @Transactional
    public void executeRollback(FaultInjectionRun run) {
        try {
            log.info("Executing rollback for run: {}", run.getRunId());

            for (String target : run.getTargets()) {
                rollbackFault(target);
                Thread.sleep(100);
            }

            run.setRollbackCompletedAt(LocalDateTime.now());
            run.setPhase("rollback_completed");
            run.setStatus("rollbacked");
            run.setEndedAt(LocalDateTime.now());
            runMapper.updateById(run);
            activeRuns.remove(run.getRunId());

            eventPublisher.publish("fault.injection.rollback_completed",
                    run.getRunId(), "fault_injection", run).subscribe();

            log.info("Rollback completed for run: {}", run.getRunId());
        } catch (Exception e) {
            log.error("Rollback failed for run: {}", run.getRunId(), e);
            run.setErrorDetail("Rollback failed: " + e.getMessage());
            run.setPhase("rollback_failed");
            runMapper.updateById(run);
        }
    }

    @Scheduled(fixedRate = 5000)
    @Transactional
    public void monitorActiveRuns() {
        for (Map.Entry<String, FaultInjectionRun> entry : activeRuns.entrySet()) {
            FaultInjectionRun run = entry.getValue();
            if ("running".equals(run.getStatus())) {
                FaultScenario scenario = scenarioMapper.selectOne(
                        new LambdaQueryWrapper<FaultScenario>()
                                .eq(FaultScenario::getScenarioId, run.getScenarioId()));

                if (scenario != null && scenario.getRollbackTimeoutMs() != null) {
                    long elapsed = System.currentTimeMillis() -
                            java.sql.Timestamp.valueOf(run.getStartedAt()).getTime();
                    if (elapsed > scenario.getRollbackTimeoutMs() && !run.getRollbackTriggered()) {
                        log.warn("Rollback timeout exceeded for run: {}", run.getRunId());
                        triggerRollback(run.getRunId(), "Rollback timeout exceeded").subscribe();
                    }
                }
            }
        }
    }

    private boolean isScenarioRunning(String scenarioId) {
        LambdaQueryWrapper<FaultInjectionRun> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FaultInjectionRun::getScenarioId, scenarioId)
                .eq(FaultInjectionRun::getStatus, "running");
        return runMapper.selectCount(wrapper) > 0;
    }

    private List<String> resolveTargets(Map<String, Object> scope) {
        List<String> targets = new ArrayList<>();
        if (scope.containsKey("pods")) {
            targets.addAll((List<String>) scope.get("pods"));
        }
        if (scope.containsKey("services")) {
            List<String> services = (List<String>) scope.get("services");
            for (String service : services) {
                targets.add("service:" + service);
            }
        }
        if (scope.containsKey("nodes")) {
            List<String> nodes = (List<String>) scope.get("nodes");
            for (String node : nodes) {
                targets.add("node:" + node);
            }
        }
        if (targets.isEmpty()) {
            targets.add("target-" + UUID.randomUUID().toString().substring(0, 8));
        }
        return targets;
    }

    private void validateFaultType(String faultType) {
        List<String> validTypes = Arrays.asList(
                "network_latency", "network_loss", "network_partition",
                "cpu_stress", "memory_stress", "disk_fill",
                "process_kill", "pod_delete", "service_unavailable",
                "http_error", "grpc_error", "db_connection_failure"
        );
        if (!validTypes.contains(faultType)) {
            throw BusinessException.validationError("不支持的故障类型: " + faultType);
        }
    }

    private void validateScope(Map<String, Object> scope) {
        if (scope == null || scope.isEmpty()) {
            throw BusinessException.validationError("注入范围不能为空");
        }
        boolean hasValidScope = scope.containsKey("pods") ||
                scope.containsKey("services") ||
                scope.containsKey("nodes") ||
                scope.containsKey("namespaces");
        if (!hasValidScope) {
            throw BusinessException.validationError("注入范围必须包含pods、services、nodes或namespaces");
        }
    }

    private void validateFaultConfig(String faultType, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw BusinessException.validationError("故障配置不能为空");
        }

        switch (faultType) {
            case "network_latency":
                if (!config.containsKey("latencyMs")) {
                    throw BusinessException.validationError("网络延迟故障需要配置latencyMs");
                }
                break;
            case "network_loss":
                if (!config.containsKey("lossPercent")) {
                    throw BusinessException.validationError("网络丢包故障需要配置lossPercent");
                }
                break;
            case "cpu_stress":
                if (!config.containsKey("cores")) {
                    throw BusinessException.validationError("CPU压力故障需要配置cores");
                }
                break;
            case "memory_stress":
                if (!config.containsKey("percent")) {
                    throw BusinessException.validationError("内存压力故障需要配置percent");
                }
                break;
        }
    }

    private void injectFault(String target, FaultScenario scenario) {
        log.info("Injecting fault: {} on target: {}", scenario.getFaultType(), target);
    }

    private void rollbackFault(String target) {
        log.info("Rolling back fault on target: {}", target);
    }

    private void checkHealthAndAutoRollback(FaultInjectionRun run, FaultScenario scenario) {
        Map<String, Object> metrics = run.getMetrics();
        if (metrics == null) return;

        BigDecimal errorRateThreshold = (BigDecimal) metrics.get("errorRateThreshold");
        if (errorRateThreshold != null) {
            double currentErrorRate = checkCurrentErrorRate(run);
            if (currentErrorRate > errorRateThreshold.doubleValue()) {
                log.warn("Error rate exceeded threshold: {} > {}", currentErrorRate, errorRateThreshold);
                triggerRollback(run.getRunId(), "Error rate threshold exceeded: " + currentErrorRate).subscribe();
            }
        }

        BigDecimal latencyThreshold = (BigDecimal) metrics.get("latencyP99Threshold");
        if (latencyThreshold != null) {
            double currentLatency = checkCurrentLatencyP99(run);
            if (currentLatency > latencyThreshold.doubleValue()) {
                log.warn("Latency P99 exceeded threshold: {} > {}", currentLatency, latencyThreshold);
                triggerRollback(run.getRunId(), "Latency P99 threshold exceeded: " + currentLatency).subscribe();
            }
        }
    }

    private double checkCurrentErrorRate(FaultInjectionRun run) {
        return Math.random() * 0.1;
    }

    private double checkCurrentLatencyP99(FaultInjectionRun run) {
        return 100 + Math.random() * 200;
    }
}
