package com.solo.config.module.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.solo.config.common.IdGenerator;
import com.solo.config.entity.AuditLog;
import com.solo.config.entity.Command;
import com.solo.config.mapper.AuditLogMapper;
import com.solo.config.mapper.CommandMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final CommandMapper commandMapper;

    public Mono<Command> recordCommand(String commandType, String aggregateId,
                                       Map<String, Object> payload, Map<String, Object> metadata) {
        return Mono.fromCallable(() -> {
            Command command = new Command();
            command.setCommandId(IdGenerator.generateCommandId());
            command.setCommandType(commandType);
            command.setAggregateId(aggregateId);
            command.setPayload(payload);
            command.setMetadata(metadata);
            command.setStatus("pending");
            command.setTimestamp(LocalDateTime.now());

            commandMapper.insert(command);
            log.info("Command recorded: {}, id: {}", commandType, command.getCommandId());

            return command;
        });
    }

    public Mono<Command> updateCommandStatus(String commandId, String status, Map<String, Object> result) {
        return Mono.fromCallable(() -> {
            Command command = commandMapper.selectOne(
                    new QueryWrapper<Command>().eq("command_id", commandId)
            );

            if (command != null) {
                command.setStatus(status);
                command.setResult(result);
                commandMapper.updateById(command);
                log.info("Command status updated: {}, status: {}", commandId, status);
            }

            return command;
        });
    }

    public Mono<AuditLog> recordAuditLog(String userId, String operation,
                                         String resourceType, String resourceId,
                                         Map<String, Object> oldValue, Map<String, Object> newValue,
                                         String ipAddress, String userAgent) {
        return Mono.fromCallable(() -> {
            AuditLog auditLog = new AuditLog();
            auditLog.setAuditId(IdGenerator.generateAuditId());
            auditLog.setUserId(userId);
            auditLog.setOperation(operation);
            auditLog.setResourceType(resourceType);
            auditLog.setResourceId(resourceId);
            auditLog.setOldValue(oldValue);
            auditLog.setNewValue(newValue);
            auditLog.setIpAddress(ipAddress);
            auditLog.setUserAgent(userAgent);
            auditLog.setTimestamp(LocalDateTime.now());

            auditLogMapper.insert(auditLog);
            log.debug("Audit log recorded: {}, resource: {}", operation, resourceId);

            return auditLog;
        });
    }

    public Flux<Command> listCommands(String commandType, String status, int page, int size) {
        return Flux.fromIterable(
                commandMapper.selectList(
                        new QueryWrapper<Command>()
                                .eq(commandType != null, "command_type", commandType)
                                .eq(status != null, "status", status)
                                .orderByDesc("timestamp")
                                .last("LIMIT " + size + " OFFSET " + (page - 1) * size)
                )
        );
    }

    public Mono<Command> getCommand(String commandId) {
        return Mono.justOrEmpty(
                commandMapper.selectOne(
                        new QueryWrapper<Command>().eq("command_id", commandId)
                )
        );
    }

    public Flux<AuditLog> listAuditLogs(String userId, String resourceType,
                                        String resourceId, String operation,
                                        LocalDateTime startTime, LocalDateTime endTime,
                                        int page, int size) {
        return Flux.fromIterable(
                auditLogMapper.selectList(
                        new QueryWrapper<AuditLog>()
                                .eq(userId != null, "user_id", userId)
                                .eq(resourceType != null, "resource_type", resourceType)
                                .eq(resourceId != null, "resource_id", resourceId)
                                .eq(operation != null, "operation", operation)
                                .ge(startTime != null, "timestamp", startTime)
                                .le(endTime != null, "timestamp", endTime)
                                .orderByDesc("timestamp")
                                .last("LIMIT " + size + " OFFSET " + (page - 1) * size)
                )
        );
    }

    public Mono<AuditLog> getAuditLog(String auditId) {
        return Mono.justOrEmpty(
                auditLogMapper.selectOne(
                        new QueryWrapper<AuditLog>().eq("audit_id", auditId)
                )
        );
    }

    public Mono<Map<String, Object>> generateComplianceReport(LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            List<AuditLog> logs = auditLogMapper.selectList(
                    new QueryWrapper<AuditLog>()
                            .ge("timestamp", startTime)
                            .le("timestamp", endTime)
                            .orderByAsc("timestamp")
            );

            Map<String, Object> report = new java.util.HashMap<>();
            report.put("startTime", startTime);
            report.put("endTime", endTime);
            report.put("totalOperations", logs.size());

            Map<String, Integer> operationCount = new java.util.HashMap<>();
            Map<String, Integer> userCount = new java.util.HashMap<>();
            Map<String, Integer> resourceCount = new java.util.HashMap<>();

            for (AuditLog log : logs) {
                operationCount.merge(log.getOperation(), 1, Integer::sum);
                if (log.getUserId() != null) {
                    userCount.merge(log.getUserId(), 1, Integer::sum);
                }
                if (log.getResourceType() != null) {
                    resourceCount.merge(log.getResourceType(), 1, Integer::sum);
                }
            }

            report.put("operationBreakdown", operationCount);
            report.put("userBreakdown", userCount);
            report.put("resourceBreakdown", resourceCount);
            report.put("generatedAt", LocalDateTime.now());

            return report;
        });
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldLogs() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(365);
        int deleted = auditLogMapper.delete(
                new QueryWrapper<AuditLog>().lt("timestamp", threshold)
        );
        log.info("Cleaned up {} old audit logs", deleted);
    }

    public Flux<Command> getCommandsByAggregate(String aggregateId) {
        return Flux.fromIterable(
                commandMapper.selectList(
                        new QueryWrapper<Command>()
                                .eq("aggregate_id", aggregateId)
                                .orderByAsc("timestamp")
                )
        );
    }
}
