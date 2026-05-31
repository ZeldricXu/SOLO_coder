package com.meshcontrol.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.audit.dto.*;
import com.meshcontrol.audit.entity.AuditLog;
import com.meshcontrol.audit.entity.CommandLog;
import com.meshcontrol.audit.mapper.AuditLogMapper;
import com.meshcontrol.audit.mapper.CommandLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService extends BaseService<CommandLogMapper, CommandLog> {

    private final CommandLogMapper commandLogMapper;
    private final AuditLogMapper auditLogMapper;

    @Transactional
    public CommandLog recordCommand(CommandRecordRequest request) {
        CommandLog commandLog = new CommandLog();
        commandLog.setCommandId(IdGenerator.generateId("cmd"));
        commandLog.setCommandType(request.getCommandType());
        commandLog.setAggregateId(request.getAggregateId());
        commandLog.setAggregateType(request.getAggregateType());
        commandLog.setPayload(request.getPayload());
        commandLog.setMetadata(request.getMetadata() != null ? request.getMetadata() : new HashMap<>());
        commandLog.setStatus("pending");
        commandLog.setExecutedBy(request.getExecutedBy() != null ? request.getExecutedBy() : "system");
        commandLog.setExecutedAt(LocalDateTime.now());

        commandLogMapper.insert(commandLog);
        log.debug("Command recorded: {} type: {}", commandLog.getCommandId(), commandLog.getCommandType());
        return commandLog;
    }

    @Transactional
    public boolean updateCommandResult(String commandId, String status, Map<String, Object> result, String errorMessage) {
        CommandLog commandLog = commandLogMapper.selectById(commandId);
        if (commandLog == null) {
            return false;
        }

        commandLog.setStatus(status);
        commandLog.setResult(result);
        commandLog.setErrorMessage(errorMessage);
        if (commandLog.getExecutedAt() != null) {
            commandLog.setDurationMs(java.time.Duration.between(commandLog.getExecutedAt(), LocalDateTime.now()).toMillis());
        }

        commandLogMapper.updateById(commandLog);
        log.debug("Command result updated: {} status: {}", commandId, status);
        return true;
    }

    public IPage<CommandLog> queryCommands(CommandQueryRequest request) {
        LambdaQueryWrapper<CommandLog> wrapper = new LambdaQueryWrapper<>();
        if (request.getCommandType() != null) {
            wrapper.eq(CommandLog::getCommandType, request.getCommandType());
        }
        if (request.getAggregateId() != null) {
            wrapper.eq(CommandLog::getAggregateId, request.getAggregateId());
        }
        if (request.getAggregateType() != null) {
            wrapper.eq(CommandLog::getAggregateType, request.getAggregateType());
        }
        if (request.getExecutedBy() != null) {
            wrapper.eq(CommandLog::getExecutedBy, request.getExecutedBy());
        }
        if (request.getStatus() != null) {
            wrapper.eq(CommandLog::getStatus, request.getStatus());
        }
        if (request.getStartTime() != null) {
            wrapper.ge(CommandLog::getExecutedAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(CommandLog::getExecutedAt, request.getEndTime());
        }
        wrapper.orderByDesc(CommandLog::getExecutedAt);
        return page(request.getPageNum(), request.getPageSize(), wrapper);
    }

    public CommandLog getCommand(String commandId) {
        return commandLogMapper.selectById(commandId);
    }

    @Transactional
    public AuditLog createAuditLog(String action, String resourceType, String resourceId,
                                   Map<String, Object> oldValue, Map<String, Object> newValue,
                                   String operator, String sourceIp, String userAgent) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId(IdGenerator.generateId("aud"));
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setOperator(operator != null ? operator : "system");
        auditLog.setSourceIp(sourceIp);
        auditLog.setUserAgent(userAgent);

        auditLogMapper.insert(auditLog);
        log.debug("Audit log created: {} action: {} resource: {}",
                auditLog.getAuditId(), action, resourceType + "/" + resourceId);
        return auditLog;
    }

    @Transactional
    public boolean linkAuditToCommand(String auditId, String commandId) {
        AuditLog auditLog = auditLogMapper.selectById(auditId);
        if (auditLog == null) {
            return false;
        }
        auditLog.setCommandId(commandId);
        auditLogMapper.updateById(auditLog);
        return true;
    }

    @Transactional
    public boolean linkAuditToEvent(String auditId, String eventId) {
        AuditLog auditLog = auditLogMapper.selectById(auditId);
        if (auditLog == null) {
            return false;
        }
        auditLog.setEventId(eventId);
        auditLogMapper.updateById(auditLog);
        return true;
    }

    public IPage<AuditLog> queryAuditLogs(AuditQueryRequest request) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (request.getResourceType() != null) {
            wrapper.eq(AuditLog::getResourceType, request.getResourceType());
        }
        if (request.getResourceId() != null) {
            wrapper.eq(AuditLog::getResourceId, request.getResourceId());
        }
        if (request.getOperator() != null) {
            wrapper.eq(AuditLog::getOperator, request.getOperator());
        }
        if (request.getAction() != null) {
            wrapper.eq(AuditLog::getAction, request.getAction());
        }
        if (request.getStartTime() != null) {
            wrapper.ge(AuditLog::getCreatedAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(AuditLog::getCreatedAt, request.getEndTime());
        }
        wrapper.orderByDesc(AuditLog::getCreatedAt);
        return page(request.getPageNum(), request.getPageSize(), wrapper);
    }

    public List<AuditLog> getAuditLogsForResource(String resourceType, String resourceId) {
        return auditLogMapper.findByResource(resourceType, resourceId);
    }

    public List<AuditLog> getAuditLogsForCommand(String commandId) {
        return auditLogMapper.findByCommandId(commandId);
    }

    public Map<String, Object> generateComplianceReport(ComplianceReportRequest request) {
        List<CommandLog> commands = commandLogMapper.findByTimeRange(request.getStartTime(), request.getEndTime());
        List<AuditLog> audits = auditLogMapper.findByTimeRange(request.getStartTime(), request.getEndTime());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportGeneratedAt", LocalDateTime.now());
        report.put("reportPeriod", Map.of(
                "startTime", request.getStartTime(),
                "endTime", request.getEndTime()
        ));

        Map<String, Object> commandStats = new HashMap<>();
        commandStats.put("totalCommands", commands.size());
        commandStats.put("successCount", commands.stream().filter(c -> "success".equals(c.getStatus())).count());
        commandStats.put("failedCount", commands.stream().filter(c -> "failed".equals(c.getStatus())).count());
        commandStats.put("pendingCount", commands.stream().filter(c -> "pending".equals(c.getStatus())).count());

        Map<String, Long> commandTypeStats = new HashMap<>();
        for (CommandLog cmd : commands) {
            commandTypeStats.merge(cmd.getCommandType(), 1L, Long::sum);
        }
        commandStats.put("byType", commandTypeStats);
        report.put("commandStatistics", commandStats);

        Map<String, Object> auditStats = new HashMap<>();
        auditStats.put("totalAuditLogs", audits.size());

        Set<String> uniqueOperators = new HashSet<>();
        Set<String> uniqueResources = new HashSet<>();
        Map<String, Long> actionStats = new HashMap<>();

        for (AuditLog audit : audits) {
            if (audit.getOperator() != null) {
                uniqueOperators.add(audit.getOperator());
            }
            if (audit.getResourceType() != null) {
                uniqueResources.add(audit.getResourceType() + "/" + audit.getResourceId());
            }
            if (audit.getAction() != null) {
                actionStats.merge(audit.getAction(), 1L, Long::sum);
            }
        }

        auditStats.put("uniqueOperators", uniqueOperators.size());
        auditStats.put("uniqueResources", uniqueResources.size());
        auditStats.put("byAction", actionStats);
        report.put("auditStatistics", auditStats);

        if ("detailed".equals(request.getReportFormat())) {
            report.put("commands", commands);
            report.put("auditLogs", audits);
        }

        return report;
    }

    @EventListener
    public void handleEvent(Object event) {
        log.debug("Received event for audit: {}", event.getClass().getSimpleName());
    }

    public Map<String, Object> getAuditStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCommands", commandLogMapper.selectCount(null));
        stats.put("totalAuditLogs", auditLogMapper.selectCount(null));
        return stats;
    }

    public List<Map<String, Object>> getCommandTimeline(String aggregateId, String aggregateType) {
        List<CommandLog> commands = commandLogMapper.findByAggregate(aggregateId, aggregateType);
        List<AuditLog> audits = auditLogMapper.findByResource(aggregateType, aggregateId);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (CommandLog cmd : commands) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "command");
            entry.put("id", cmd.getCommandId());
            entry.put("timestamp", cmd.getExecutedAt());
            entry.put("commandType", cmd.getCommandType());
            entry.put("status", cmd.getStatus());
            timeline.add(entry);
        }
        for (AuditLog audit : audits) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "audit");
            entry.put("id", audit.getAuditId());
            entry.put("timestamp", audit.getCreatedAt());
            entry.put("action", audit.getAction());
            entry.put("operator", audit.getOperator());
            timeline.add(entry);
        }

        timeline.sort((a, b) -> {
            LocalDateTime t1 = (LocalDateTime) a.get("timestamp");
            LocalDateTime t2 = (LocalDateTime) b.get("timestamp");
            return t2.compareTo(t1);
        });

        return timeline;
    }
}
