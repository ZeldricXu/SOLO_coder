package com.schedulebook.controller;

import com.schedulebook.config.DispatchStrategyConfig;
import com.schedulebook.config.LockTimeoutConfig;
import com.schedulebook.config.ReminderIntervalConfig;
import com.schedulebook.dto.ApiResponse;
import com.schedulebook.service.AdjustmentDetectionQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    @Autowired
    private LockTimeoutConfig lockTimeoutConfig;

    @Autowired
    private ReminderIntervalConfig reminderIntervalConfig;

    @Autowired
    private DispatchStrategyConfig dispatchStrategyConfig;

    @Autowired(required = false)
    private AdjustmentDetectionQueueService queueService;

    @GetMapping("/lock-timeouts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getLockTimeouts() {
        return ResponseEntity.ok(ApiResponse.success(lockTimeoutConfig.getTimeoutSeconds()));
    }

    @PutMapping("/lock-timeouts/{urgencyLevel}")
    public ResponseEntity<ApiResponse<Map<String, Long>>> updateLockTimeout(
            @PathVariable String urgencyLevel,
            @RequestParam long timeoutSeconds) {
        
        if (lockTimeoutConfig.isValidUrgencyLevel(urgencyLevel)) {
            lockTimeoutConfig.getTimeoutSeconds().put(urgencyLevel, timeoutSeconds);
            return ResponseEntity.ok(ApiResponse.success("锁定超时配置已更新", lockTimeoutConfig.getTimeoutSeconds()));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "无效的紧急程度: " + urgencyLevel));
    }

    @GetMapping("/reminder-rules")
    public ResponseEntity<ApiResponse<Map<String, List<ReminderIntervalConfig.ReminderRule>>>> getReminderRules() {
        return ResponseEntity.ok(ApiResponse.success(reminderIntervalConfig.getRules()));
    }

    @GetMapping("/reminder-rules/{category}")
    public ResponseEntity<ApiResponse<List<ReminderIntervalConfig.ReminderRule>>> getReminderRulesByCategory(
            @PathVariable String category) {
        List<ReminderIntervalConfig.ReminderRule> rules = reminderIntervalConfig.getRules().get(category);
        if (rules != null) {
            return ResponseEntity.ok(ApiResponse.success(rules));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "无效的时长分类: " + category));
    }

    @GetMapping("/dispatch-strategies")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDispatchStrategies() {
        Map<String, Object> result = new HashMap<>();
        result.put("defaultStrategy", dispatchStrategyConfig.getDefaultStrategy());
        result.put("strategies", dispatchStrategyConfig.getStrategies());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/dispatch-strategies/default")
    public ResponseEntity<ApiResponse<String>> setDefaultDispatchStrategy(
            @RequestParam String strategyName) {
        
        if (dispatchStrategyConfig.isStrategyEnabled(strategyName)) {
            dispatchStrategyConfig.setDefaultStrategy(strategyName);
            return ResponseEntity.ok(ApiResponse.success("默认调度策略已更新为: " + strategyName, strategyName));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "策略不存在或未启用: " + strategyName));
    }

    @PutMapping("/dispatch-strategies/{strategyName}/toggle")
    public ResponseEntity<ApiResponse<Boolean>> toggleDispatchStrategy(
            @PathVariable String strategyName,
            @RequestParam boolean enabled) {
        
        DispatchStrategyConfig.StrategyConfig config = dispatchStrategyConfig.getStrategyConfig(strategyName);
        if (config != null) {
            config.setEnabled(enabled);
            return ResponseEntity.ok(ApiResponse.success("策略状态已更新", enabled));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "策略不存在: " + strategyName));
    }

    @GetMapping("/queue/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        if (queueService != null) {
            status.put("running", queueService.isRunning());
            status.put("queueSize", queueService.getQueueSize());
        } else {
            status.put("running", false);
            status.put("queueSize", 0);
            status.put("message", "队列服务未启用");
        }
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @PostMapping("/queue/start")
    public ResponseEntity<ApiResponse<String>> startQueueWorker() {
        if (queueService != null) {
            queueService.startWorker();
            return ResponseEntity.ok(ApiResponse.success("队列Worker已启动", "started"));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "队列服务未启用"));
    }

    @PostMapping("/queue/stop")
    public ResponseEntity<ApiResponse<String>> stopQueueWorker() {
        if (queueService != null) {
            queueService.stopWorker();
            return ResponseEntity.ok(ApiResponse.success("队列Worker已停止", "stopped"));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "队列服务未启用"));
    }

    @DeleteMapping("/queue/clear")
    public ResponseEntity<ApiResponse<String>> clearQueue() {
        if (queueService != null) {
            queueService.clearQueue();
            return ResponseEntity.ok(ApiResponse.success("队列已清空", "cleared"));
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "队列服务未启用"));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfigSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        Map<String, Object> lockConfig = new HashMap<>();
        lockConfig.put("timeouts", lockTimeoutConfig.getTimeoutSeconds());
        summary.put("lock", lockConfig);
        
        Map<String, Object> reminderConfig = new HashMap<>();
        reminderConfig.put("rules", reminderIntervalConfig.getRules());
        summary.put("reminder", reminderConfig);
        
        Map<String, Object> dispatchConfig = new HashMap<>();
        dispatchConfig.put("defaultStrategy", dispatchStrategyConfig.getDefaultStrategy());
        dispatchConfig.put("strategies", dispatchStrategyConfig.getStrategies());
        summary.put("dispatch", dispatchConfig);
        
        if (queueService != null) {
            Map<String, Object> queueConfig = new HashMap<>();
            queueConfig.put("running", queueService.isRunning());
            queueConfig.put("queueSize", queueService.getQueueSize());
            summary.put("queue", queueConfig);
        }
        
        return ResponseEntity.ok(ApiResponse.success(summary));
    }
}
