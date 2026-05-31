package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.plugin.PluginManager;
import com.metricplatform.plugin.impl.AuditLogPlugin;
import com.metricplatform.plugin.impl.DataEncryptionPlugin;
import com.metricplatform.plugin.impl.DataDesensitizationPlugin;
import com.metricplatform.plugin.impl.SqlPerformancePlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginManager pluginManager;

    @GetMapping
    public Mono<ApiResponse<List<PluginManager.PluginInfo>>> getAllPlugins() {
        return Mono.just(ApiResponse.success(pluginManager.getAllPluginInfo()));
    }

    @GetMapping("/{pluginName}")
    public Mono<ApiResponse<PluginManager.PluginInfo>> getPlugin(@PathVariable String pluginName) {
        PluginManager.PluginInfo plugin = pluginManager.getAllPluginInfo().stream()
                .filter(p -> p.getName().equals(pluginName))
                .findFirst()
                .orElse(null);

        if (plugin != null) {
            return Mono.just(ApiResponse.success(plugin));
        } else {
            return Mono.just(ApiResponse.notFound("插件不存在"));
        }
    }

    @PostMapping("/{pluginName}/enable")
    public Mono<ApiResponse<Map<String, Object>>> enablePlugin(@PathVariable String pluginName) {
        try {
            pluginManager.enablePlugin(pluginName);
            Map<String, Object> result = Map.of(
                    "pluginName", pluginName,
                    "enabled", true,
                    "message", "插件已启用"
            );
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @PostMapping("/{pluginName}/disable")
    public Mono<ApiResponse<Map<String, Object>>> disablePlugin(@PathVariable String pluginName) {
        try {
            pluginManager.disablePlugin(pluginName);
            Map<String, Object> result = Map.of(
                    "pluginName", pluginName,
                    "enabled", false,
                    "message", "插件已禁用"
            );
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @GetMapping("/{pluginName}/enabled")
    public Mono<ApiResponse<Map<String, Object>>> isPluginEnabled(@PathVariable String pluginName) {
        boolean enabled = pluginManager.isPluginEnabled(pluginName);
        Map<String, Object> result = Map.of(
                "pluginName", pluginName,
                "enabled", enabled
        );
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping("/sql-performance/threshold")
    public Mono<ApiResponse<Map<String, Object>>> setSlowSqlThreshold(@RequestBody Map<String, Object> request) {
        SqlPerformancePlugin plugin = (SqlPerformancePlugin) pluginManager.getPlugin("sql-performance");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("SQL性能监控插件不存在"));
        }

        Long threshold = ((Number) request.get("threshold")).longValue();
        plugin.setSlowSqlThreshold(threshold);

        Map<String, Object> result = Map.of(
                "pluginName", "sql-performance",
                "slowSqlThreshold", threshold,
                "message", "慢SQL阈值已更新"
        );
        return Mono.just(ApiResponse.success(result));
    }

    @GetMapping("/sql-performance/threshold")
    public Mono<ApiResponse<Map<String, Object>>> getSlowSqlThreshold() {
        SqlPerformancePlugin plugin = (SqlPerformancePlugin) pluginManager.getPlugin("sql-performance");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("SQL性能监控插件不存在"));
        }

        Map<String, Object> result = Map.of(
                "pluginName", "sql-performance",
                "slowSqlThreshold", plugin.getSlowSqlThreshold()
        );
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping("/data-encryption/fields")
    public Mono<ApiResponse<Map<String, Object>>> addEncryptedField(@RequestBody Map<String, Object> request) {
        DataEncryptionPlugin plugin = (DataEncryptionPlugin) pluginManager.getPlugin("data-encryption");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("数据加密插件不存在"));
        }

        String fieldName = (String) request.get("fieldName");
        plugin.addEncryptedField(fieldName);

        Map<String, Object> result = Map.of(
                "pluginName", "data-encryption",
                "fieldName", fieldName,
                "encryptedFields", plugin.getEncryptedFields(),
                "message", "加密字段已添加"
        );
        return Mono.just(ApiResponse.success(result));
    }

    @DeleteMapping("/data-encryption/fields/{fieldName}")
    public Mono<ApiResponse<Map<String, Object>>> removeEncryptedField(@PathVariable String fieldName) {
        DataEncryptionPlugin plugin = (DataEncryptionPlugin) pluginManager.getPlugin("data-encryption");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("数据加密插件不存在"));
        }

        plugin.removeEncryptedField(fieldName);

        Map<String, Object> result = Map.of(
                "pluginName", "data-encryption",
                "fieldName", fieldName,
                "encryptedFields", plugin.getEncryptedFields(),
                "message", "加密字段已移除"
        );
        return Mono.just(ApiResponse.success(result));
    }

    @GetMapping("/data-encryption/fields")
    public Mono<ApiResponse<Map<String, Object>>> getEncryptedFields() {
        DataEncryptionPlugin plugin = (DataEncryptionPlugin) pluginManager.getPlugin("data-encryption");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("数据加密插件不存在"));
        }

        Map<String, Object> result = Map.of(
                "pluginName", "data-encryption",
                "encryptedFields", plugin.getEncryptedFields()
        );
        return Mono.just(ApiResponse.success(result));
    }

    @GetMapping("/audit-logs")
    public Mono<ApiResponse<List<AuditLogPlugin.AuditRecord>>> getAuditLogs(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String operation) {

        AuditLogPlugin plugin = (AuditLogPlugin) pluginManager.getPlugin("audit-log");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("审计日志插件不存在"));
        }

        List<AuditLogPlugin.AuditRecord> records;

        if (tableName != null) {
            records = plugin.getAuditLogsByTable(tableName, limit);
        } else if (operator != null) {
            records = plugin.getAuditLogsByOperator(operator, limit);
        } else if (operation != null) {
            records = plugin.getAuditLogsByOperation(operation, limit);
        } else {
            records = plugin.getRecentAuditLogs(limit);
        }

        return Mono.just(ApiResponse.success(records));
    }

    @GetMapping("/audit-logs/stats")
    public Mono<ApiResponse<Map<String, Long>>> getAuditStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        AuditLogPlugin plugin = (AuditLogPlugin) pluginManager.getPlugin("audit-log");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("审计日志插件不存在"));
        }

        if (startTime == null) {
            startTime = LocalDateTime.now().minusHours(24);
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }

        Map<String, Long> stats = plugin.getAuditStats(startTime, endTime);
        stats.put("historySize", (long) plugin.getHistorySize());

        return Mono.just(ApiResponse.success(stats));
    }

    @DeleteMapping("/audit-logs")
    public Mono<ApiResponse<Map<String, Object>>> clearAuditLogs() {
        AuditLogPlugin plugin = (AuditLogPlugin) pluginManager.getPlugin("audit-log");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("审计日志插件不存在"));
        }

        plugin.clearAuditHistory();
        Map<String, Object> result = Map.of(
                "message", "审计日志已清空",
                "clearedAt", LocalDateTime.now()
        );
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping("/data-desensitization/desensitizers")
    public Mono<ApiResponse<Map<String, Object>>> registerDesensitizer(@RequestBody Map<String, Object> request) {
        DataDesensitizationPlugin plugin = (DataDesensitizationPlugin) pluginManager.getPlugin("data-desensitization");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("数据脱敏插件不存在"));
        }

        String fieldType = (String) request.get("fieldType");
        plugin.registerDesensitizer(fieldType, value -> {
            String pattern = (String) request.getOrDefault("pattern", "****");
            if (value == null || value.length() <= 4) {
                return pattern;
            }
            return value.substring(0, 2) + pattern + value.substring(value.length() - 2);
        });

        Map<String, Object> result = Map.of(
                "pluginName", "data-desensitization",
                "fieldType", fieldType,
                "message", "脱敏器已注册"
        );
        return Mono.just(ApiResponse.success(result));
    }

    @DeleteMapping("/data-desensitization/desensitizers/{fieldType}")
    public Mono<ApiResponse<Map<String, Object>>> unregisterDesensitizer(@PathVariable String fieldType) {
        DataDesensitizationPlugin plugin = (DataDesensitizationPlugin) pluginManager.getPlugin("data-desensitization");
        if (plugin == null) {
            return Mono.just(ApiResponse.notFound("数据脱敏插件不存在"));
        }

        plugin.unregisterDesensitizer(fieldType);

        Map<String, Object> result = Map.of(
                "pluginName", "data-desensitization",
                "fieldType", fieldType,
                "message", "脱敏器已移除"
        );
        return Mono.just(ApiResponse.success(result));
    }
}
