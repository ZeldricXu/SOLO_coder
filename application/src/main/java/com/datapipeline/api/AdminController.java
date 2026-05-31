package com.datapipeline.api;

import com.datapipeline.common.dto.ApiResponse;
import com.datapipeline.common.model.StatisticsSnapshot;
import com.datapipeline.core.metrics.MetricsRecorder;
import com.datapipeline.dp.budget.BudgetAccount;
import com.datapipeline.dp.budget.PrivacyBudgetManager;
import com.datapipeline.fl.model.TrainingTask;
import com.datapipeline.fl.coordinator.FederatedCoordinator;
import com.datapipeline.monitoring.alert.AlertEvent;
import com.datapipeline.monitoring.alert.AlertRule;
import com.datapipeline.monitoring.alert.AlertRuleEngine;
import com.datapipeline.monitoring.stats.HistogramStats;
import com.datapipeline.monitoring.stats.StatisticsCollector;
import com.datapipeline.scheduler.ScheduledTask;
import com.datapipeline.scheduler.TaskScheduler;
import com.datapipeline.scheduler.TaskStats;
import com.datapipeline.scheduler.TaskTracker;
import com.datapipeline.tee.enclave.EnclaveInstance;
import com.datapipeline.tee.enclave.EnclaveManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final MetricsRecorder metricsRecorder;
    private final StatisticsCollector statisticsCollector;
    private final AlertRuleEngine alertRuleEngine;
    private final TaskScheduler taskScheduler;
    private final TaskTracker taskTracker;
    private final PrivacyBudgetManager privacyBudgetManager;
    private final FederatedCoordinator federatedCoordinator;
    private final EnclaveManager enclaveManager;

    @GetMapping("/metrics")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getMetrics() {
        return Mono.fromCallable(() -> {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("totalRequests", metricsRecorder.getTotalRequests());
            metrics.put("successRequests", metricsRecorder.getSuccessRequests());
            metrics.put("failedRequests", metricsRecorder.getFailedRequests());
            metrics.put("timeoutRequests", metricsRecorder.getTimeoutRequests());
            metrics.put("averageLatencyMs", metricsRecorder.getAverageLatencyMs());
            metrics.put("maxLatencyMs", metricsRecorder.getMaxLatencyMs());
            metrics.put("errorRate", metricsRecorder.getErrorRate());
            return ResponseEntity.ok(ApiResponse.success(metrics));
        });
    }

    @GetMapping("/snapshot")
    public Mono<ResponseEntity<ApiResponse<StatisticsSnapshot>>> getSnapshot() {
        return Mono.fromCallable(() -> {
            StatisticsSnapshot snapshot = metricsRecorder.snapshot(Map.of(
                    "host", "localhost",
                    "region", "default"
            ));
            return ResponseEntity.ok(ApiResponse.success(snapshot));
        });
    }

    @PostMapping("/alerts/rules")
    public Mono<ResponseEntity<ApiResponse<AlertRule>>> createAlertRule(@RequestBody AlertRule rule) {
        return Mono.fromCallable(() -> {
            if (rule.getRuleId() == null) {
                rule.setRuleId(UUID.randomUUID().toString());
            }
            alertRuleEngine.addRule(rule);
            log.info("Alert rule created: id={}, metric={}", rule.getRuleId(), rule.getMetricName());
            return ResponseEntity.ok(ApiResponse.success(rule));
        });
    }

    @GetMapping("/alerts/rules")
    public Mono<ResponseEntity<ApiResponse<List<AlertRule>>>> listAlertRules() {
        return Mono.fromCallable(() -> ResponseEntity.ok(ApiResponse.success(alertRuleEngine.getRules())));
    }

    @GetMapping("/alerts/active")
    public Mono<ResponseEntity<ApiResponse<Map<String, AlertEvent>>>> listActiveAlerts() {
        return Mono.fromCallable(() -> ResponseEntity.ok(ApiResponse.success(alertRuleEngine.getActiveAlerts())));
    }

    @PostMapping("/tasks/one-time")
    public Mono<ResponseEntity<ApiResponse<ScheduledTask>>> scheduleOneTimeTask(
            @RequestBody Map<String, Object> request) {

        return Mono.fromCallable(() -> {
            String name = (String) request.getOrDefault("name", "unnamed-task");
            long delayMs = ((Number) request.getOrDefault("delayMs", 0)).longValue();
            Duration delay = Duration.ofMillis(delayMs);

            ScheduledTask task = taskScheduler.scheduleOneTime(name, () -> {
                log.info("Executing one-time task: {}", name);
            }, delay, request);

            return ResponseEntity.ok(ApiResponse.success(task));
        });
    }

    @GetMapping("/tasks")
    public Mono<ResponseEntity<ApiResponse<List<ScheduledTask>>>> listTasks() {
        return Mono.fromCallable(() -> ResponseEntity.ok(ApiResponse.success(taskScheduler.getAllTasks())));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<ApiResponse<ScheduledTask>>> getTask(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            ScheduledTask task = taskScheduler.getTask(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            return ResponseEntity.ok(ApiResponse.success(task));
        });
    }

    @DeleteMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> cancelTask(@PathVariable String taskId) {
        return Mono.fromCallable(() -> {
            boolean cancelled = taskScheduler.cancelTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(cancelled));
        });
    }

    @GetMapping("/tasks/stats")
    public Mono<ResponseEntity<ApiResponse<Map<String, TaskStats>>>> getTaskStats() {
        return Mono.fromCallable(() -> ResponseEntity.ok(ApiResponse.success(taskTracker.getAllTaskStats())));
    }

    @PostMapping("/privacy/accounts")
    public Mono<ResponseEntity<ApiResponse<BudgetAccount>>> createPrivacyAccount(
            @RequestBody Map<String, Object> request) {

        return Mono.fromCallable(() -> {
            String accountId = (String) request.getOrDefault("accountId", UUID.randomUUID().toString());
            double epsilon = ((Number) request.getOrDefault("epsilon", 1.0)).doubleValue();
            double delta = ((Number) request.getOrDefault("delta", 1e-5)).doubleValue();

            BudgetAccount account = privacyBudgetManager.createAccount(accountId, epsilon, delta);
            log.info("Privacy budget account created: id={}, epsilon={}, delta={}", accountId, epsilon, delta);
            return ResponseEntity.ok(ApiResponse.success(account));
        });
    }

    @GetMapping("/privacy/accounts/{accountId}")
    public Mono<ResponseEntity<ApiResponse<BudgetAccount>>> getPrivacyAccount(@PathVariable String accountId) {
        return Mono.fromCallable(() -> {
            BudgetAccount account = privacyBudgetManager.getAccount(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
            return ResponseEntity.ok(ApiResponse.success(account));
        });
    }

    @GetMapping("/privacy/accounts/{accountId}/status")
    public Mono<ResponseEntity<ApiResponse<PrivacyBudgetManager.Status>>> getBudgetStatus(@PathVariable String accountId) {
        return Mono.fromCallable(() -> {
            PrivacyBudgetManager.Status status = privacyBudgetManager.getBudgetStatus(accountId);
            return ResponseEntity.ok(ApiResponse.success(status));
        });
    }

    @PostMapping("/fl/tasks")
    public Mono<ResponseEntity<ApiResponse<TrainingTask>>> createFLTask(@RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            String modelName = (String) request.getOrDefault("modelName", "default-model");
            int totalRounds = ((Number) request.getOrDefault("totalRounds", 10)).intValue();
            int minParticipants = ((Number) request.getOrDefault("minParticipants", 3)).intValue();
            long timeoutMs = ((Number) request.getOrDefault("roundTimeoutMs", 60000)).longValue();

            TrainingTask task = federatedCoordinator.createTask(
                    modelName, totalRounds, minParticipants,
                    Duration.ofMillis(timeoutMs), request
            );
            log.info("FL task created: id={}, model={}", task.getTaskId(), modelName);
            return ResponseEntity.ok(ApiResponse.success(task));
        });
    }

    @GetMapping("/fl/tasks")
    public Mono<ResponseEntity<ApiResponse<List<TrainingTask>>>> listFLTasks() {
        return Mono.fromCallable(() -> ResponseEntity.ok(ApiResponse.success(federatedCoordinator.getAllTasks())));
    }

    @PostMapping("/tee/enclaves")
    public Mono<ResponseEntity<ApiResponse<EnclaveInstance>>> createEnclave(@RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            String name = (String) request.getOrDefault("name", "default-enclave");
            long memorySize = ((Number) request.getOrDefault("memorySize", 128 * 1024 * 1024)).longValue();

            EnclaveInstance enclave = enclaveManager.createEnclave(name, memorySize);
            enclaveManager.initializeEnclave(enclave.getEnclaveId());
            log.info("Enclave created: id={}, type={}", enclave.getEnclaveId(), enclave.getEnclaveType());
            return ResponseEntity.ok(ApiResponse.success(enclave));
        });
    }

    @GetMapping("/tee/enclaves")
    public Mono<ResponseEntity<ApiResponse<List<EnclaveInstance>>>> listEnclaves() {
        return Mono.fromCallable(() -> ResponseEntity.ok(ApiResponse.success(enclaveManager.getAllEnclaves())));
    }

    @PostMapping("/tee/enclaves/{id}/start")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> startEnclave(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            boolean started = enclaveManager.startEnclave(id);
            return ResponseEntity.ok(ApiResponse.success(started));
        });
    }

    @PostMapping("/tee/enclaves/{id}/terminate")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> terminateEnclave(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            boolean terminated = enclaveManager.terminateEnclave(id);
            return ResponseEntity.ok(ApiResponse.success(terminated));
        });
    }

    @GetMapping("/stats/{name}")
    public Mono<ResponseEntity<ApiResponse<HistogramStats>>> getHistogramStats(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            HistogramStats stats = statisticsCollector.getHistogramStats(name);
            return ResponseEntity.ok(ApiResponse.success(stats));
        });
    }

}
