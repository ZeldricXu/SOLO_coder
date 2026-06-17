package com.enterprise.risk.observability.controller;

import com.enterprise.risk.common.rule.RuleDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规则管理CRUD控制器
 * 提供规则的创建、更新、删除、启用/禁用功能，操作后自动触发规则热加载
 */
@Slf4j
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleManagementController {

    private static final String RULE_DEFINITIONS_KEY = "risk:rule_definitions";
    private static final String RULE_HOT_RELOAD_TOPIC = "risk.rule.hot_reload";
    private static final String RULE_AUDIT_LOG_KEY = "risk:rule_audit_log";

    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 创建新规则
     */
    @PostMapping
    public ResponseEntity<RuleOperationResponse> createRule(@Valid @RequestBody RuleDefinition rule,
                                                            @RequestParam(required = false) String operator) {
        if (rule.getRuleId() == null || rule.getRuleId().isEmpty()) {
            rule.setRuleId("RULE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        }
        rule.setCreatedAt(Instant.now().toEpochMilli());
        rule.setUpdatedAt(Instant.now().toEpochMilli());
        if (rule.getVersion() == null) {
            rule.setVersion(1);
        }

        RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
        if (ruleMap.containsKey(rule.getRuleId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(RuleOperationResponse.failure(rule.getRuleId(), "规则ID已存在"));
        }

        ruleMap.put(rule.getRuleId(), rule);
        writeAuditLog(rule.getRuleId(), operator, "CREATE", rule);
        triggerHotReload(rule.getRuleId(), HotReloadType.CREATE);

        log.info("[RuleManagementController] 规则创建成功: ruleId={}, operator={}", rule.getRuleId(), operator);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RuleOperationResponse.success(rule.getRuleId(), rule, "规则创建成功"));
    }

    /**
     * 更新规则
     */
    @PutMapping("/{ruleId}")
    public ResponseEntity<RuleOperationResponse> updateRule(@PathVariable String ruleId,
                                                            @Valid @RequestBody RuleDefinition rule,
                                                            @RequestParam(required = false) String operator) {
        RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
        RuleDefinition existing = ruleMap.get(ruleId);

        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(RuleOperationResponse.failure(ruleId, "规则不存在"));
        }

        rule.setRuleId(ruleId);
        rule.setCreatedAt(existing.getCreatedAt());
        rule.setUpdatedAt(Instant.now().toEpochMilli());
        rule.setVersion(existing.getVersion() == null ? 2 : existing.getVersion() + 1);

        ruleMap.put(ruleId, rule);
        writeAuditLog(ruleId, operator, "UPDATE", rule);
        triggerHotReload(ruleId, HotReloadType.UPDATE);

        log.info("[RuleManagementController] 规则更新成功: ruleId={}, version={}, operator={}",
                ruleId, rule.getVersion(), operator);
        return ResponseEntity.ok(RuleOperationResponse.success(ruleId, rule, "规则更新成功"));
    }

    /**
     * 删除规则
     */
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<RuleOperationResponse> deleteRule(@PathVariable String ruleId,
                                                            @RequestParam(required = false) String operator) {
        RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
        RuleDefinition existing = ruleMap.get(ruleId);

        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(RuleOperationResponse.failure(ruleId, "规则不存在"));
        }

        ruleMap.remove(ruleId);
        writeAuditLog(ruleId, operator, "DELETE", existing);
        triggerHotReload(ruleId, HotReloadType.DELETE);

        log.info("[RuleManagementController] 规则删除成功: ruleId={}, operator={}", ruleId, operator);
        return ResponseEntity.ok(RuleOperationResponse.success(ruleId, null, "规则删除成功"));
    }

    /**
     * 启用规则
     */
    @PutMapping("/{ruleId}/enable")
    public ResponseEntity<RuleOperationResponse> enableRule(@PathVariable String ruleId,
                                                            @RequestParam(required = false) String operator) {
        return setRuleEnabled(ruleId, true, operator);
    }

    /**
     * 禁用规则
     */
    @PutMapping("/{ruleId}/disable")
    public ResponseEntity<RuleOperationResponse> disableRule(@PathVariable String ruleId,
                                                             @RequestParam(required = false) String operator) {
        return setRuleEnabled(ruleId, false, operator);
    }

    /**
     * 根据ID获取规则详情
     */
    @GetMapping("/{ruleId}")
    public ResponseEntity<RuleOperationResponse> getRule(@PathVariable String ruleId) {
        RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
        RuleDefinition rule = ruleMap.get(ruleId);

        if (rule == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(RuleOperationResponse.failure(ruleId, "规则不存在"));
        }

        return ResponseEntity.ok(RuleOperationResponse.success(ruleId, rule, "查询成功"));
    }

    /**
     * 规则列表查询（支持分页和过滤）
     */
    @GetMapping
    public ResponseEntity<RuleListResponse> listRules(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String businessLine,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String ruleType) {

        RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
        List<RuleDefinition> allRules = new ArrayList<>(ruleMap.values());

        List<RuleDefinition> filtered = new ArrayList<>();
        for (RuleDefinition rule : allRules) {
            if (businessLine != null && !businessLine.isEmpty() && !businessLine.equals(rule.getBusinessLine())) {
                continue;
            }
            if (enabled != null && !enabled.equals(rule.getEnabled())) {
                continue;
            }
            if (ruleType != null && !ruleType.isEmpty() && rule.getRuleType() != null
                    && !ruleType.equalsIgnoreCase(rule.getRuleType().name())) {
                continue;
            }
            filtered.add(rule);
        }

        filtered.sort((a, b) -> {
            long ta = a.getUpdatedAt() != null ? a.getUpdatedAt() : 0;
            long tb = b.getUpdatedAt() != null ? b.getUpdatedAt() : 0;
            return Long.compare(tb, ta);
        });

        int total = filtered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<RuleDefinition> paged = filtered.subList(fromIndex, toIndex);

        RuleListResponse response = RuleListResponse.builder()
                .page(page)
                .size(size)
                .total(total)
                .totalPages((int) Math.ceil((double) total / size))
                .rules(paged)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * 批量启用/禁用规则
     */
    @PutMapping("/batch")
    public ResponseEntity<BatchOperationResponse> batchUpdate(
            @RequestBody BatchUpdateRequest request,
            @RequestParam(required = false) String operator) {
        List<String> successIds = new ArrayList<>();
        Map<String, String> failures = new HashMap<>();

        for (String ruleId : request.getRuleIds()) {
            try {
                if ("ENABLE".equalsIgnoreCase(request.getOperation())) {
                    ResponseEntity<RuleOperationResponse> resp = setRuleEnabled(ruleId, true, operator);
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        failures.put(ruleId, "启用失败");
                    } else {
                        successIds.add(ruleId);
                    }
                } else if ("DISABLE".equalsIgnoreCase(request.getOperation())) {
                    ResponseEntity<RuleOperationResponse> resp = setRuleEnabled(ruleId, false, operator);
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        failures.put(ruleId, "禁用失败");
                    } else {
                        successIds.add(ruleId);
                    }
                } else if ("DELETE".equalsIgnoreCase(request.getOperation())) {
                    RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
                    RuleDefinition existing = ruleMap.get(ruleId);
                    if (existing != null) {
                        ruleMap.remove(ruleId);
                        writeAuditLog(ruleId, operator, "DELETE_BATCH", existing);
                        triggerHotReload(ruleId, HotReloadType.DELETE);
                        successIds.add(ruleId);
                    } else {
                        failures.put(ruleId, "规则不存在");
                    }
                }
            } catch (Exception e) {
                failures.put(ruleId, e.getMessage());
            }
        }

        log.info("[RuleManagementController] 批量操作完成: operation={}, success={}, failed={}",
                request.getOperation(), successIds.size(), failures.size());

        return ResponseEntity.ok(BatchOperationResponse.builder()
                .operation(request.getOperation())
                .successCount(successIds.size())
                .failureCount(failures.size())
                .successIds(successIds)
                .failures(failures)
                .build());
    }

    /**
     * 手动触发规则热加载
     */
    @PostMapping("/reload")
    public ResponseEntity<RuleOperationResponse> reloadRules(@RequestParam(required = false) String ruleId,
                                                              @RequestParam(required = false) String operator) {
        triggerHotReload(ruleId, HotReloadType.RELOAD);
        log.info("[RuleManagementController] 手动触发规则热加载: ruleId={}, operator={}", ruleId, operator);
        return ResponseEntity.ok(RuleOperationResponse.success(ruleId != null ? ruleId : "ALL",
                null, ruleId != null ? "单规则热加载触发成功" : "全量规则热加载触发成功"));
    }

    private ResponseEntity<RuleOperationResponse> setRuleEnabled(String ruleId, boolean enabled, String operator) {
        RMap<String, RuleDefinition> ruleMap = redissonClient.getMap(RULE_DEFINITIONS_KEY);
        RuleDefinition existing = ruleMap.get(ruleId);

        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(RuleOperationResponse.failure(ruleId, "规则不存在"));
        }

        existing.setEnabled(enabled);
        existing.setUpdatedAt(Instant.now().toEpochMilli());
        ruleMap.put(ruleId, existing);
        writeAuditLog(ruleId, operator, enabled ? "ENABLE" : "DISABLE", existing);
        triggerHotReload(ruleId, enabled ? HotReloadType.ENABLE : HotReloadType.DISABLE);

        return ResponseEntity.ok(RuleOperationResponse.success(ruleId, existing,
                enabled ? "规则已启用" : "规则已禁用"));
    }

    private void triggerHotReload(String ruleId, HotReloadType type) {
        HotReloadEvent event = HotReloadEvent.builder()
                .eventId("RELOAD-" + System.currentTimeMillis())
                .ruleId(ruleId)
                .reloadType(type)
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            kafkaTemplate.send(RULE_HOT_RELOAD_TOPIC, ruleId != null ? ruleId : "__ALL__", event);
        } catch (Exception e) {
            log.error("[RuleManagementController] 发送规则热加载Kafka事件失败", e);
        }

        try {
            eventPublisher.publishEvent(new RuleHotReloadApplicationEvent(this, event));
        } catch (Exception e) {
            log.warn("[RuleManagementController] 发布规则热加载Spring事件失败", e);
        }
    }

    private void writeAuditLog(String ruleId, String operator, String operation, Object payload) {
        try {
            AuditLogEntry entry = AuditLogEntry.builder()
                    .logId("AUDIT-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8))
                    .ruleId(ruleId)
                    .operator(operator != null ? operator : "SYSTEM")
                    .operation(operation)
                    .payload(payload != null ? objectMapper.valueToTree(payload).toString() : null)
                    .timestamp(System.currentTimeMillis())
                    .build();

            RMap<String, AuditLogEntry> auditMap = redissonClient.getMap(RULE_AUDIT_LOG_KEY);
            auditMap.put(entry.getLogId(), entry);

            @SuppressWarnings("unchecked")
            RMap<String, List<String>> ruleAuditIndex = redissonClient.getMap(RULE_AUDIT_LOG_KEY + ":index");
            List<String> logs = ruleAuditIndex.computeIfAbsent(ruleId, k -> new ArrayList<>());
            logs.add(0, entry.getLogId());
            if (logs.size() > 100) {
                logs.subList(100, logs.size()).clear();
            }
            ruleAuditIndex.put(ruleId, logs);

        } catch (Exception e) {
            log.warn("[RuleManagementController] 写入审计日志失败", e);
        }
    }

    public enum HotReloadType {
        CREATE, UPDATE, DELETE, ENABLE, DISABLE, RELOAD
    }

    public static class RuleHotReloadApplicationEvent extends ApplicationEvent {
        private final transient HotReloadEvent hotReloadEvent;

        public RuleHotReloadApplicationEvent(Object source, HotReloadEvent hotReloadEvent) {
            super(source);
            this.hotReloadEvent = hotReloadEvent;
        }

        public HotReloadEvent getHotReloadEvent() {
            return hotReloadEvent;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotReloadEvent implements Serializable {
        private String eventId;
        private String ruleId;
        private HotReloadType reloadType;
        private Long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLogEntry implements Serializable {
        private String logId;
        private String ruleId;
        private String operator;
        private String operation;
        private String payload;
        private Long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleOperationResponse implements Serializable {
        private String ruleId;
        private Boolean success;
        private String message;
        private RuleDefinition rule;
        private Long timestamp;

        public static RuleOperationResponse success(String ruleId, RuleDefinition rule, String message) {
            return RuleOperationResponse.builder()
                    .ruleId(ruleId)
                    .success(true)
                    .message(message)
                    .rule(rule)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }

        public static RuleOperationResponse failure(String ruleId, String message) {
            return RuleOperationResponse.builder()
                    .ruleId(ruleId)
                    .success(false)
                    .message(message)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleListResponse implements Serializable {
        private Integer page;
        private Integer size;
        private Integer total;
        private Integer totalPages;
        private List<RuleDefinition> rules;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchUpdateRequest implements Serializable {
        private List<String> ruleIds;
        private String operation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchOperationResponse implements Serializable {
        private String operation;
        private Integer successCount;
        private Integer failureCount;
        private List<String> successIds;
        private Map<String, String> failures;
    }
}
