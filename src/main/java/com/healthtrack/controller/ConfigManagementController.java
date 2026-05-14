package com.healthtrack.controller;

import com.healthtrack.entity.AdviceRule;
import com.healthtrack.entity.DeduplicationConfig;
import com.healthtrack.service.AdviceRuleService;
import com.healthtrack.service.DeduplicationConfigService;
import com.healthtrack.service.HealthDataQueueService;
import com.healthtrack.service.AnalysisTaskQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigManagementController {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManagementController.class);

    @Autowired
    private DeduplicationConfigService deduplicationConfigService;

    @Autowired
    private AdviceRuleService adviceRuleService;

    @Autowired
    private HealthDataQueueService healthDataQueueService;

    @Autowired
    private AnalysisTaskQueueService analysisTaskQueueService;

    @GetMapping("/deduplication")
    public ResponseEntity<List<DeduplicationConfig>> getAllDeduplicationConfigs() {
        List<DeduplicationConfig> configs = deduplicationConfigService.getAllConfigs();
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/deduplication/enabled")
    public ResponseEntity<List<DeduplicationConfig>> getEnabledDeduplicationConfigs() {
        List<DeduplicationConfig> configs = deduplicationConfigService.getEnabledConfigs();
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/deduplication/{priority}")
    public ResponseEntity<DeduplicationConfig> getDeduplicationConfig(@PathVariable String priority) {
        DeduplicationConfig config = deduplicationConfigService.getConfig(priority);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @GetMapping("/deduplication/{priority}/window")
    public ResponseEntity<Map<String, Object>> getDeduplicationWindow(@PathVariable String priority) {
        int minutes = deduplicationConfigService.getWindowMinutes(priority);
        Map<String, Object> response = new HashMap<>();
        response.put("priority", priority);
        response.put("windowMinutes", minutes);
        response.put("windowMs", (long) minutes * 60 * 1000);
        response.put("windowHours", minutes / 60.0);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/deduplication/{priority}")
    public ResponseEntity<DeduplicationConfig> updateDeduplicationConfig(
            @PathVariable String priority,
            @RequestBody Map<String, Object> request) {
        
        Integer windowMinutes = (Integer) request.get("windowMinutes");
        String description = (String) request.get("description");
        
        if (windowMinutes == null) {
            return ResponseEntity.badRequest().build();
        }
        
        DeduplicationConfig config = deduplicationConfigService.updateConfig(
                priority, windowMinutes, description);
        return ResponseEntity.ok(config);
    }

    @PostMapping("/deduplication/{priority}/enable")
    public ResponseEntity<Map<String, Boolean>> enableDeduplicationConfig(@PathVariable String priority) {
        boolean success = deduplicationConfigService.enableConfig(priority);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", success);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/deduplication/{priority}/disable")
    public ResponseEntity<Map<String, Boolean>> disableDeduplicationConfig(@PathVariable String priority) {
        boolean success = deduplicationConfigService.disableConfig(priority);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", success);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/deduplication/refresh")
    public ResponseEntity<Map<String, String>> refreshDeduplicationCache() {
        deduplicationConfigService.refreshCache();
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "去重窗口配置缓存已刷新");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/rules")
    public ResponseEntity<List<AdviceRule>> getAllRules() {
        List<AdviceRule> rules = adviceRuleService.getAllRules();
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/rules/global")
    public ResponseEntity<List<AdviceRule>> getGlobalRules() {
        List<AdviceRule> rules = adviceRuleService.getGlobalRules();
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/rules/user/{userId}")
    public ResponseEntity<List<AdviceRule>> getUserRules(@PathVariable String userId) {
        List<AdviceRule> rules = adviceRuleService.getUserRules(userId);
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/rules/{id}")
    public ResponseEntity<AdviceRule> getRuleById(@PathVariable Long id) {
        return adviceRuleService.getRuleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/rules")
    public ResponseEntity<AdviceRule> createRule(@RequestBody AdviceRule rule) {
        AdviceRule createdRule = adviceRuleService.createRule(rule);
        return ResponseEntity.ok(createdRule);
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<AdviceRule> updateRule(@PathVariable Long id, @RequestBody AdviceRule rule) {
        try {
            AdviceRule updatedRule = adviceRuleService.updateRule(id, rule);
            return ResponseEntity.ok(updatedRule);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/rules/{id}/enable")
    public ResponseEntity<Map<String, Boolean>> enableRule(@PathVariable Long id) {
        boolean success = adviceRuleService.enableRule(id);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", success);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/rules/{id}/disable")
    public ResponseEntity<Map<String, Boolean>> disableRule(@PathVariable Long id) {
        boolean success = adviceRuleService.disableRule(id);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", success);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Map<String, Boolean>> deleteRule(@PathVariable Long id) {
        try {
            adviceRuleService.deleteRule(id);
            Map<String, Boolean> response = new HashMap<>();
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/rules/refresh")
    public ResponseEntity<Map<String, String>> refreshRulesCache() {
        adviceRuleService.refreshCache();
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "建议规则缓存已刷新");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        
        Map<String, Object> queueStatus = new HashMap<>();
        queueStatus.put("healthDataQueueSize", healthDataQueueService.getQueueSize());
        queueStatus.put("healthDataDlqSize", healthDataQueueService.getDeadLetterQueueSize());
        queueStatus.put("analysisTaskQueueSize", analysisTaskQueueService.getQueueSize());
        queueStatus.put("analysisTaskDlqSize", analysisTaskQueueService.getDeadLetterQueueSize());
        status.put("queues", queueStatus);
        
        Map<String, Object> configStatus = new HashMap<>();
        configStatus.put("rulesEnabled", adviceRuleService.isRulesEnabled());
        configStatus.put("useCustomRules", adviceRuleService.isUseCustomRules());
        configStatus.put("deduplicationHighWindow", deduplicationConfigService.getDefaultHighPriorityMinutes());
        configStatus.put("deduplicationMediumWindow", deduplicationConfigService.getDefaultMediumPriorityMinutes());
        configStatus.put("deduplicationLowWindow", deduplicationConfigService.getDefaultLowPriorityMinutes());
        status.put("config", configStatus);
        
        return ResponseEntity.ok(status);
    }
}
