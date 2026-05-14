package com.reviewsystem.controller;

import com.reviewsystem.config.AuditRuleConfig;
import com.reviewsystem.config.RecommendWeightConfig;
import com.reviewsystem.queue.AuditQueueWorker;
import com.reviewsystem.queue.SentimentQueueWorker;
import com.reviewsystem.rule.AuditRuleManager;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Resource
    private AuditRuleManager auditRuleManager;

    @Resource
    private RecommendWeightConfig recommendWeightConfig;

    @Resource
    private AuditQueueWorker auditQueueWorker;

    @Resource
    private SentimentQueueWorker sentimentQueueWorker;

    @GetMapping("/audit/rules")
    public Map<String, Object> getAuditRules() {
        Map<String, Object> result = new HashMap<>();

        result.put("sensitive_words", auditRuleManager.getSensitiveWords());

        Map<String, Object> qualityCheck = new HashMap<>();
        qualityCheck.put("min_length", auditRuleManager.getMinLength());
        qualityCheck.put("max_length", auditRuleManager.getMaxLength());
        qualityCheck.put("min_quality_score", auditRuleManager.getMinQualityScore());
        qualityCheck.put("spam_detection", auditRuleManager.getSpamDetectionConfig());
        result.put("quality_check", qualityCheck);

        result.put("violation_types", auditRuleManager.getAllViolationTypes());

        return result;
    }

    @PostMapping("/audit/sensitive-words")
    public Map<String, Object> addSensitiveWord(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String word = request.get("word");

        if (word == null || word.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "敏感词不能为空");
            return result;
        }

        auditRuleManager.addSensitiveWord(word.trim());
        result.put("success", true);
        result.put("message", "敏感词已添加");
        return result;
    }

    @DeleteMapping("/audit/sensitive-words/{word}")
    public Map<String, Object> removeSensitiveWord(@PathVariable String word) {
        Map<String, Object> result = new HashMap<>();

        auditRuleManager.removeSensitiveWord(word);
        result.put("success", true);
        result.put("message", "敏感词已移除");
        return result;
    }

    @GetMapping("/recommend/weights")
    public Map<String, Object> getRecommendWeights() {
        Map<String, Object> result = new HashMap<>();

        Map<String, Object> weights = new HashMap<>();
        recommendWeightConfig.getWeights().forEach((key, item) -> {
            Map<String, Object> weightMap = new HashMap<>();
            weightMap.put("quality", item.getQuality());
            weightMap.put("sentiment", item.getSentiment());
            weightMap.put("heat", item.getHeat());
            weightMap.put("time", item.getTime());
            weightMap.put("valid", item.validate());
            weights.put(key, weightMap);
        });

        result.put("weights", weights);
        return result;
    }

    @GetMapping("/recommend/weights/{contentType}")
    public Map<String, Object> getRecommendWeight(@PathVariable String contentType) {
        Map<String, Object> result = new HashMap<>();
        RecommendWeightConfig.WeightItem weight = recommendWeightConfig.getWeightByContentType(contentType);

        Map<String, Object> weightMap = new HashMap<>();
        weightMap.put("content_type", contentType);
        weightMap.put("quality", weight.getQuality());
        weightMap.put("sentiment", weight.getSentiment());
        weightMap.put("heat", weight.getHeat());
        weightMap.put("time", weight.getTime());
        weightMap.put("valid", weight.validate());

        result.put("success", true);
        result.put("weight", weightMap);
        return result;
    }

    @GetMapping("/queue/status")
    public Map<String, Object> getQueueStatus() {
        Map<String, Object> result = new HashMap<>();

        Map<String, Object> auditQueue = new HashMap<>();
        auditQueue.put("pending", auditQueueWorker.getPendingCount());
        auditQueue.put("processing", auditQueueWorker.getProcessingCount());
        auditQueue.put("dead", auditQueueWorker.getDeadCount());
        result.put("audit_queue", auditQueue);

        Map<String, Object> sentimentQueue = new HashMap<>();
        sentimentQueue.put("pending", sentimentQueueWorker.getPendingCount());
        sentimentQueue.put("processing", sentimentQueueWorker.getProcessingCount());
        sentimentQueue.put("dead", sentimentQueueWorker.getDeadCount());
        result.put("sentiment_queue", sentimentQueue);

        return result;
    }

    @PostMapping("/queue/audit/clear")
    public Map<String, Object> clearAuditQueue() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "审核队列清除操作需要运维权限，此接口仅用于测试");
        return result;
    }

    @PostMapping("/audit/rules/refresh")
    public Map<String, Object> refreshAuditRules() {
        Map<String, Object> result = new HashMap<>();
        auditRuleManager.refreshRules();
        result.put("success", true);
        result.put("message", "审核规则已刷新");
        result.put("sensitive_words_count", auditRuleManager.getSensitiveWords().size());
        result.put("violation_types_count", auditRuleManager.getAllViolationTypes().size());
        return result;
    }

    @GetMapping("/violation-types")
    public Map<String, Object> getViolationTypes() {
        Map<String, Object> result = new HashMap<>();
        result.put("violation_types", auditRuleManager.getAllViolationTypes());
        return result;
    }

    @PostMapping("/violation-types")
    public Map<String, Object> addViolationType(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();

        String type = (String) request.get("type");
        if (type == null || type.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "违规类型不能为空");
            return result;
        }

        AuditRuleConfig.ViolationType vt = new AuditRuleConfig.ViolationType();
        vt.setName((String) request.getOrDefault("name", type));
        vt.setPriority((Integer) request.getOrDefault("priority", 50));
        vt.setAutoReject((Boolean) request.getOrDefault("auto_reject", false));

        auditRuleManager.addViolationType(type, vt);
        result.put("success", true);
        result.put("message", "违规类型已添加");
        return result;
    }

    @DeleteMapping("/violation-types/{type}")
    public Map<String, Object> removeViolationType(@PathVariable String type) {
        Map<String, Object> result = new HashMap<>();
        auditRuleManager.removeViolationType(type);
        result.put("success", true);
        result.put("message", "违规类型已移除");
        return result;
    }
}
