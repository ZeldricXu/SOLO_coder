package com.metricplatform.plugin.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.context.RequestContext;
import com.metricplatform.plugin.MybatisPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class, ResultHandler.class})
})
@RequiredArgsConstructor
public class AuditLogPlugin implements MybatisPlugin {

    private final ObjectMapper objectMapper;

    @Value("${plugin.audit-log.enabled:true}")
    private boolean enabled;

    @Value("${plugin.audit-log.max-history:10000}")
    private int maxHistory;

    private final Deque<AuditRecord> auditHistory = new ConcurrentLinkedDeque<>();

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class AuditRecord {
        private String auditId;
        private String sqlId;
        private String operation;
        private String tableName;
        private String operator;
        private String clientIp;
        private String userAgent;
        private Object parameter;
        private long executionTime;
        private boolean success;
        private String errorMessage;
        private LocalDateTime timestamp;
    }

    @Override
    public String getName() {
        return "audit-log";
    }

    @Override
    public String getDescription() {
        return "审计日志插件，记录所有数据库操作，支持操作追溯和安全审计";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getOrder() {
        return 400;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!isEnabled()) {
            return invocation.proceed();
        }

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
        String sqlId = ms.getId();
        SqlCommandType commandType = ms.getSqlCommandType();

        long startTime = System.currentTimeMillis();
        AuditRecord.AuditRecordBuilder builder = AuditRecord.builder()
                .auditId("audit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .sqlId(sqlId)
                .operation(commandType.name())
                .tableName(extractTableName(sqlId))
                .timestamp(LocalDateTime.now());

        fillOperatorInfo(builder);

        try {
            if (parameter != null) {
                builder.parameter(sanitizeParameter(parameter));
            }

            Object result = invocation.proceed();

            long executionTime = System.currentTimeMillis() - startTime;
            builder.executionTime(executionTime)
                    .success(true);

            recordAudit(builder.build());

            return result;
        } catch (Throwable t) {
            long executionTime = System.currentTimeMillis() - startTime;
            builder.executionTime(executionTime)
                    .success(false)
                    .errorMessage(t.getMessage());

            recordAudit(builder.build());
            throw t;
        }
    }

    private void fillOperatorInfo(AuditRecord.AuditRecordBuilder builder) {
        try {
            RequestContext.RequestInfo requestInfo = RequestContext.getRequestInfo();
            if (requestInfo != null) {
                builder.clientIp(requestInfo.getClientIp() != null ? requestInfo.getClientIp() : "unknown");
                builder.userAgent(requestInfo.getUserAgent());
                builder.operator(requestInfo.getUser() != null ? requestInfo.getUser() : "system");
            } else {
                builder.operator("system");
                builder.clientIp("127.0.0.1");
            }
        } catch (Exception e) {
            builder.operator("system");
            builder.clientIp("unknown");
        }
    }

    private String extractTableName(String sqlId) {
        String[] parts = sqlId.split("\\.");
        if (parts.length > 0) {
            String mapperName = parts[parts.length - 2];
            return mapperName.replace("Mapper", "").replaceAll("([A-Z])", "_$1").toLowerCase();
        }
        return "unknown";
    }

    private Object sanitizeParameter(Object parameter) {
        try {
            String json = objectMapper.writeValueAsString(parameter);
            if (json.length() > 2000) {
                json = json.substring(0, 2000) + "...[truncated]";
            }
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return parameter != null ? parameter.toString() : null;
        }
    }

    private void recordAudit(AuditRecord record) {
        auditHistory.addFirst(record);
        while (auditHistory.size() > maxHistory) {
            auditHistory.removeLast();
        }

        if (log.isDebugEnabled()) {
            log.debug("审计记录: [{}] {} {} by {} | {}ms",
                    record.getOperation(),
                    record.getTableName(),
                    record.isSuccess() ? "SUCCESS" : "FAILED",
                    record.getOperator(),
                    record.getExecutionTime());
        }
    }

    @Override
    public void setProperties(java.util.Properties properties) {
    }

    public List<AuditRecord> getRecentAuditLogs(int limit) {
        List<AuditRecord> records = new ArrayList<>();
        Iterator<AuditRecord> iterator = auditHistory.iterator();
        while (iterator.hasNext() && records.size() < limit) {
            records.add(iterator.next());
        }
        return records;
    }

    public List<AuditRecord> getAuditLogsByTable(String tableName, int limit) {
        List<AuditRecord> records = new ArrayList<>();
        for (AuditRecord record : auditHistory) {
            if (tableName.equalsIgnoreCase(record.getTableName()) && records.size() < limit) {
                records.add(record);
            }
        }
        return records;
    }

    public List<AuditRecord> getAuditLogsByOperator(String operator, int limit) {
        List<AuditRecord> records = new ArrayList<>();
        for (AuditRecord record : auditHistory) {
            if (operator.equals(record.getOperator()) && records.size() < limit) {
                records.add(record);
            }
        }
        return records;
    }

    public List<AuditRecord> getAuditLogsByOperation(String operation, int limit) {
        List<AuditRecord> records = new ArrayList<>();
        for (AuditRecord record : auditHistory) {
            if (operation.equalsIgnoreCase(record.getOperation()) && records.size() < limit) {
                records.add(record);
            }
        }
        return records;
    }

    public Map<String, Long> getAuditStats(LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", 0L);
        stats.put("success", 0L);
        stats.put("failed", 0L);

        for (SqlCommandType type : SqlCommandType.values()) {
            stats.put(type.name().toLowerCase(), 0L);
        }

        for (AuditRecord record : auditHistory) {
            if (record.getTimestamp().isBefore(startTime) || record.getTimestamp().isAfter(endTime)) {
                continue;
            }

            stats.merge("total", 1L, Long::sum);
            stats.merge(record.isSuccess() ? "success" : "failed", 1L, Long::sum);
            stats.merge(record.getOperation().toLowerCase(), 1L, Long::sum);
        }

        return stats;
    }

    public void clearAuditHistory() {
        auditHistory.clear();
        log.info("审计日志历史已清空");
    }

    public int getHistorySize() {
        return auditHistory.size();
    }
}
