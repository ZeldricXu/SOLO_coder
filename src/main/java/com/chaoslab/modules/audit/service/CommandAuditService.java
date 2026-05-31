package com.chaoslab.modules.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.JsonUtils;
import com.chaoslab.entity.AuditLog;
import com.chaoslab.entity.CommandLog;
import com.chaoslab.entity.ComplianceReport;
import com.chaoslab.event.DomainEvent;
import com.chaoslab.event.EventPublisher;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.AuditLogMapper;
import com.chaoslab.mapper.CommandLogMapper;
import com.chaoslab.mapper.ComplianceReportMapper;
import com.chaoslab.modules.audit.dto.AuditLogQueryRequest;
import com.chaoslab.modules.audit.dto.CommandSubmitRequest;
import com.chaoslab.modules.audit.dto.ComplianceReportRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandAuditService {

    private final CommandLogMapper commandLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final ComplianceReportMapper reportMapper;
    private final EventPublisher eventPublisher;

    private final Map<String, CommandHandler> commandHandlers = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface CommandHandler {
        Map<String, Object> handle(Map<String, Object> payload, Map<String, Object> metadata) throws Exception;
    }

    public void registerHandler(String commandType, CommandHandler handler) {
        commandHandlers.put(commandType, handler);
        log.info("Registered command handler for type: {}", commandType);
    }

    @Transactional
    public Mono<CommandLog> submitCommand(CommandSubmitRequest request) {
        return Mono.fromCallable(() -> {
            CommandLog commandLog = new CommandLog();
            commandLog.setCommandId("cmd-" + UUID.randomUUID().toString().substring(0, 8));
            commandLog.setCommandType(request.getCommandType());
            commandLog.setAggregateId(request.getAggregateId());
            commandLog.setPayload(request.getPayload());
            commandLog.setMetadata(request.getMetadata());
            commandLog.setStatus("pending");
            commandLog.setCreatedBy(request.getCreatedBy());
            commandLog.setCreatedAt(LocalDateTime.now());
            commandLog.setUpdatedAt(LocalDateTime.now());

            commandLogMapper.insert(commandLog);
            log.info("Submitted command: {} type: {}", commandLog.getCommandId(), request.getCommandType());

            executeCommandAsync(commandLog);

            return commandLog;
        });
    }

    @Async
    @Transactional
    public void executeCommandAsync(CommandLog commandLog) {
        try {
            commandLog.setStatus("executing");
            commandLog.setExecutedAt(LocalDateTime.now());
            commandLog.setUpdatedAt(LocalDateTime.now());
            commandLogMapper.updateById(commandLog);

            eventPublisher.publish("command.started",
                    commandLog.getCommandId(), "command", commandLog).subscribe();

            Map<String, Object> result = executeCommand(commandLog);

            commandLog.setStatus("completed");
            commandLog.setResult(result);
            commandLog.setCompletedAt(LocalDateTime.now());
            commandLog.setUpdatedAt(LocalDateTime.now());
            commandLogMapper.updateById(commandLog);

            eventPublisher.publish("command.completed",
                    commandLog.getCommandId(), "command", commandLog).subscribe();

            createAuditLogForCommand(commandLog, null, result);

            log.info("Command completed: {}", commandLog.getCommandId());
        } catch (Exception e) {
            log.error("Command failed: {}", commandLog.getCommandId(), e);
            commandLog.setStatus("failed");
            commandLog.setErrorDetail(e.getMessage());
            commandLog.setCompletedAt(LocalDateTime.now());
            commandLog.setUpdatedAt(LocalDateTime.now());
            commandLogMapper.updateById(commandLog);

            eventPublisher.publish("command.failed",
                    commandLog.getCommandId(), "command", commandLog).subscribe();
        }
    }

    private Map<String, Object> executeCommand(CommandLog commandLog) throws Exception {
        CommandHandler handler = commandHandlers.get(commandLog.getCommandType());
        if (handler != null) {
            return handler.handle(commandLog.getPayload(), commandLog.getMetadata());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("commandId", commandLog.getCommandId());
        result.put("processed", true);
        result.put("timestamp", LocalDateTime.now().toString());
        return result;
    }

    public Mono<CommandLog> getCommand(String commandId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CommandLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CommandLog::getCommandId, commandId);
            CommandLog commandLog = commandLogMapper.selectOne(wrapper);
            if (commandLog == null) {
                throw BusinessException.notFound("命令不存在: " + commandId);
            }
            return commandLog;
        });
    }

    public Mono<List<CommandLog>> listCommands(String status, String commandType, String createdBy) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CommandLog> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(CommandLog::getStatus, status);
            }
            if (commandType != null && !commandType.isEmpty()) {
                wrapper.eq(CommandLog::getCommandType, commandType);
            }
            if (createdBy != null && !createdBy.isEmpty()) {
                wrapper.eq(CommandLog::getCreatedBy, createdBy);
            }
            wrapper.orderByDesc(CommandLog::getCreatedAt);
            return commandLogMapper.selectList(wrapper);
        });
    }

    @Transactional
    public AuditLog createAuditLog(String action, String actor, String resourceType, String resourceId,
                                   Map<String, Object> oldValue, Map<String, Object> newValue,
                                   String ipAddress, String userAgent, List<String> complianceTags,
                                   String commandId, String eventId) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAuditId("aud-" + UUID.randomUUID().toString().substring(0, 8));
        auditLog.setAction(action);
        auditLog.setActor(actor);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLog.setIpAddress(ipAddress);
        auditLog.setUserAgent(userAgent);
        auditLog.setComplianceTags(complianceTags);
        auditLog.setCommandId(commandId);
        auditLog.setEventId(eventId);
        auditLog.setCreatedAt(LocalDateTime.now());

        auditLogMapper.insert(auditLog);
        log.debug("Created audit log: {} action: {}", auditLog.getAuditId(), action);
        return auditLog;
    }

    private void createAuditLogForCommand(CommandLog commandLog,
                                          Map<String, Object> oldValue,
                                          Map<String, Object> result) {
        createAuditLog(
                commandLog.getCommandType(),
                commandLog.getCreatedBy() != null ? commandLog.getCreatedBy() : "system",
                "command",
                commandLog.getCommandId(),
                oldValue,
                result,
                null,
                null,
                Collections.singletonList("command_execution"),
                commandLog.getCommandId(),
                null
        );
    }

    @EventListener
    @Transactional
    public <T> void handleDomainEventForAudit(DomainEvent<T> event) {
        String actor = event.getMetadata() != null && event.getMetadata().containsKey("actor")
                ? (String) event.getMetadata().get("actor")
                : "system";

        String ipAddress = event.getMetadata() != null && event.getMetadata().containsKey("ipAddress")
                ? (String) event.getMetadata().get("ipAddress")
                : null;

        Map<String, Object> payloadMap = JsonUtils.fromJson(JsonUtils.toJson(event.getPayload()),
                new TypeReference<Map<String, Object>>() {});

        createAuditLog(
                event.getEventType(),
                actor,
                event.getAggregateType(),
                event.getAggregateId(),
                null,
                payloadMap,
                ipAddress,
                null,
                Collections.singletonList("event_" + event.getEventType()),
                null,
                event.getEventId()
        );
    }

    public Mono<List<AuditLog>> queryAuditLogs(AuditLogQueryRequest request) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
            if (request.getActor() != null && !request.getActor().isEmpty()) {
                wrapper.eq(AuditLog::getActor, request.getActor());
            }
            if (request.getAction() != null && !request.getAction().isEmpty()) {
                wrapper.eq(AuditLog::getAction, request.getAction());
            }
            if (request.getResourceType() != null && !request.getResourceType().isEmpty()) {
                wrapper.eq(AuditLog::getResourceType, request.getResourceType());
            }
            if (request.getResourceId() != null && !request.getResourceId().isEmpty()) {
                wrapper.eq(AuditLog::getResourceId, request.getResourceId());
            }
            if (request.getStartTime() != null) {
                wrapper.ge(AuditLog::getCreatedAt, request.getStartTime());
            }
            if (request.getEndTime() != null) {
                wrapper.le(AuditLog::getCreatedAt, request.getEndTime());
            }
            wrapper.orderByDesc(AuditLog::getCreatedAt);
            return auditLogMapper.selectList(wrapper);
        });
    }

    @Transactional
    public Mono<ComplianceReport> generateComplianceReport(ComplianceReportRequest request) {
        return Mono.fromCallable(() -> {
            ComplianceReport report = new ComplianceReport();
            report.setReportId("cpr-" + UUID.randomUUID().toString().substring(0, 8));
            report.setName(request.getName());
            report.setType(request.getType());
            report.setPeriodStart(request.getPeriodStart() != null ? request.getPeriodStart()
                    : LocalDateTime.now().minusDays(30));
            report.setPeriodEnd(request.getPeriodEnd() != null ? request.getPeriodEnd()
                    : LocalDateTime.now());
            report.setFilters(request.getFilters());
            report.setGeneratedBy(request.getGeneratedBy());
            report.setStatus("generating");
            report.setCreatedAt(LocalDateTime.now());
            report.setUpdatedAt(LocalDateTime.now());

            reportMapper.insert(report);
            log.info("Created compliance report: {} type: {}", report.getReportId(), request.getType());

            generateReportAsync(report);

            return report;
        });
    }

    @Async
    @Transactional
    public void generateReportAsync(ComplianceReport report) {
        try {
            LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.ge(AuditLog::getCreatedAt, report.getPeriodStart())
                    .le(AuditLog::getCreatedAt, report.getPeriodEnd());

            if (report.getFilters() != null) {
                if (report.getFilters().containsKey("actor")) {
                    wrapper.eq(AuditLog::getActor, report.getFilters().get("actor"));
                }
                if (report.getFilters().containsKey("action")) {
                    wrapper.eq(AuditLog::getAction, report.getFilters().get("action"));
                }
            }

            List<AuditLog> auditLogs = auditLogMapper.selectList(wrapper);

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalRecords", auditLogs.size());

            Map<String, Long> actionCounts = new HashMap<>();
            Map<String, Long> actorCounts = new HashMap<>();
            for (AuditLog log : auditLogs) {
                actionCounts.merge(log.getAction(), 1L, Long::sum);
                actorCounts.merge(log.getActor(), 1L, Long::sum);
            }
            summary.put("actionDistribution", actionCounts);
            summary.put("actorDistribution", actorCounts);

            Map<String, Object> details = new HashMap<>();
            details.put("auditLogs", auditLogs);

            report.setSummary(summary);
            report.setDetails(details);
            report.setStatus("completed");
            report.setGeneratedAt(LocalDateTime.now());
            report.setFilePath("/reports/" + report.getReportId() + ".pdf");
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);

            log.info("Completed compliance report: {} with {} records", report.getReportId(), auditLogs.size());
        } catch (Exception e) {
            log.error("Compliance report generation failed: {}", report.getReportId(), e);
            report.setStatus("failed");
            report.setUpdatedAt(LocalDateTime.now());
            reportMapper.updateById(report);
        }
    }

    public Mono<ComplianceReport> getReport(String reportId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ComplianceReport> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ComplianceReport::getReportId, reportId);
            ComplianceReport report = reportMapper.selectOne(wrapper);
            if (report == null) {
                throw BusinessException.notFound("合规报告不存在: " + reportId);
            }
            return report;
        });
    }

    public Mono<List<ComplianceReport>> listReports(String type, String status) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ComplianceReport> wrapper = new LambdaQueryWrapper<>();
            if (type != null && !type.isEmpty()) {
                wrapper.eq(ComplianceReport::getType, type);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ComplianceReport::getStatus, status);
            }
            wrapper.orderByDesc(ComplianceReport::getCreatedAt);
            return reportMapper.selectList(wrapper);
        });
    }

    public Mono<Map<String, Object>> getAuditStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<CommandLog> commandWrapper = new LambdaQueryWrapper<>();
            commandWrapper.eq(CommandLog::getStatus, "completed");
            stats.put("totalCommandsCompleted", commandLogMapper.selectCount(commandWrapper));

            commandWrapper = new LambdaQueryWrapper<>();
            commandWrapper.eq(CommandLog::getStatus, "failed");
            stats.put("totalCommandsFailed", commandLogMapper.selectCount(commandWrapper));

            LambdaQueryWrapper<AuditLog> auditWrapper = new LambdaQueryWrapper<>();
            stats.put("totalAuditLogs", auditLogMapper.selectCount(auditWrapper));

            LambdaQueryWrapper<ComplianceReport> reportWrapper = new LambdaQueryWrapper<>();
            reportWrapper.eq(ComplianceReport::getStatus, "completed");
            stats.put("totalReportsGenerated", reportMapper.selectCount(reportWrapper));

            return stats;
        });
    }
}
