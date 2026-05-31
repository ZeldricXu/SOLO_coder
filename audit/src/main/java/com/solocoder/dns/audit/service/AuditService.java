package com.solocoder.dns.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.entity.CommandLog;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.audit.model.AuditLog;
import com.solocoder.dns.persistence.entity.AuditLogPO;
import com.solocoder.dns.persistence.entity.CommandLogPO;
import com.solocoder.dns.persistence.mapper.AuditLogMapper;
import com.solocoder.dns.persistence.mapper.CommandLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogMapper auditLogMapper;
    private final CommandLogMapper commandLogMapper;

    public void logCommand(CommandLog command) {
        CommandLogPO po = new CommandLogPO();
        po.setCommandId(command.getCommandId());
        po.setCommandType(command.getCommandType());
        po.setAggregateId(command.getAggregateId());
        po.setPayload(JsonUtils.toJson(command.getPayload()));
        po.setUserId(command.getUserId());
        po.setIssuedAt(command.getIssuedAt());
        po.setStatus(command.getStatus());
        po.setResult(command.getResult());
        commandLogMapper.insert(po);
        log.debug("Command logged: {} - {}", command.getCommandId(), command.getCommandType());
    }

    public void logAudit(AuditLog auditLog) {
        auditLog.setLogId(IdGenerator.generateId("audit"));
        auditLog.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(toPO(auditLog));
        log.debug("Audit log recorded: {} - {}", auditLog.getLogId(), auditLog.getAction());
    }

    public AuditLog logAction(String userId, String action, String resourceType, String resourceId,
                              Map<String, Object> beforeState, Map<String, Object> afterState,
                              String clientIp, String userAgent) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(action);
        auditLog.setResourceType(resourceType);
        auditLog.setResourceId(resourceId);
        auditLog.setBeforeState(beforeState);
        auditLog.setAfterState(afterState);
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        logAudit(auditLog);
        return auditLog;
    }

    public PageResult<AuditLog> queryAuditLogs(int page, int size, String userId, String action, String resourceType) {
        LambdaQueryWrapper<AuditLogPO> wrapper = new LambdaQueryWrapper<>();
        if (userId != null && !userId.isEmpty()) {
            wrapper.eq(AuditLogPO::getUserId, userId);
        }
        if (action != null && !action.isEmpty()) {
            wrapper.eq(AuditLogPO::getAction, action);
        }
        if (resourceType != null && !resourceType.isEmpty()) {
            wrapper.eq(AuditLogPO::getResourceType, resourceType);
        }
        wrapper.orderByDesc(AuditLogPO::getCreatedAt);

        Page<AuditLogPO> poPage = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        List<AuditLog> items = poPage.getRecords().stream().map(this::toDomain).collect(Collectors.toList());
        return new PageResult<>(items, poPage.getTotal(), page, size);
    }

    public List<AuditLog> getAuditLogsForResource(String resourceType, String resourceId) {
        LambdaQueryWrapper<AuditLogPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AuditLogPO::getResourceType, resourceType);
        wrapper.eq(AuditLogPO::getResourceId, resourceId);
        wrapper.orderByDesc(AuditLogPO::getCreatedAt);
        return auditLogMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public Map<String, Object> generateComplianceReport(LocalDateTime startDate, LocalDateTime endDate) {
        LambdaQueryWrapper<AuditLogPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(AuditLogPO::getCreatedAt, startDate, endDate);
        Long totalActions = auditLogMapper.selectCount(wrapper);

        LambdaQueryWrapper<CommandLogPO> cmdWrapper = new LambdaQueryWrapper<>();
        cmdWrapper.between(CommandLogPO::getIssuedAt, startDate, endDate);
        Long totalCommands = commandLogMapper.selectCount(cmdWrapper);

        return Map.of(
                "period", Map.of("start", startDate, "end", endDate),
                "totalActions", totalActions,
                "totalCommands", totalCommands,
                "generatedAt", LocalDateTime.now()
        );
    }

    private AuditLogPO toPO(AuditLog auditLog) {
        AuditLogPO po = new AuditLogPO();
        po.setLogId(auditLog.getLogId());
        po.setCommandId(auditLog.getCommandId());
        po.setUserId(auditLog.getUserId());
        po.setAction(auditLog.getAction());
        po.setResourceType(auditLog.getResourceType());
        po.setResourceId(auditLog.getResourceId());
        po.setBeforeState(JsonUtils.toJson(auditLog.getBeforeState()));
        po.setAfterState(JsonUtils.toJson(auditLog.getAfterState()));
        po.setCreatedAt(auditLog.getCreatedAt());
        po.setClientIp(auditLog.getClientIp());
        po.setUserAgent(auditLog.getUserAgent());
        return po;
    }

    @SuppressWarnings("unchecked")
    private AuditLog toDomain(AuditLogPO po) {
        AuditLog auditLog = new AuditLog();
        auditLog.setLogId(po.getLogId());
        auditLog.setCommandId(po.getCommandId());
        auditLog.setUserId(po.getUserId());
        auditLog.setAction(po.getAction());
        auditLog.setResourceType(po.getResourceType());
        auditLog.setResourceId(po.getResourceId());
        auditLog.setBeforeState(po.getBeforeState() != null ? JsonUtils.fromJson(po.getBeforeState(), Map.class) : null);
        auditLog.setAfterState(po.getAfterState() != null ? JsonUtils.fromJson(po.getAfterState(), Map.class) : null);
        auditLog.setCreatedAt(po.getCreatedAt());
        auditLog.setClientIp(po.getClientIp());
        auditLog.setUserAgent(po.getUserAgent());
        return auditLog;
    }
}
