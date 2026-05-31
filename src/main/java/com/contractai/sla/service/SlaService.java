package com.contractai.sla.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.context.TenantContext;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.sla.dto.SlaDTO;
import com.contractai.sla.entity.SlaEscalation;
import com.contractai.sla.entity.SlaPolicy;
import com.contractai.sla.entity.SlaRecord;
import com.contractai.sla.mapper.SlaEscalationMapper;
import com.contractai.sla.mapper.SlaPolicyMapper;
import com.contractai.sla.mapper.SlaRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlaService {

    private final SlaPolicyMapper policyMapper;
    private final SlaRecordMapper recordMapper;
    private final SlaEscalationMapper escalationMapper;

    @Transactional
    public SlaPolicy createPolicy(SlaDTO.PolicyCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validatePolicyCreate(dto, tenantId);

        SlaPolicy policy = new SlaPolicy();
        policy.setId(IdUtil.getSnowflakeNextId());
        policy.setTenantId(tenantId);
        policy.setPolicyCode(dto.getPolicyCode());
        policy.setPolicyName(dto.getPolicyName());
        policy.setSlaType(dto.getSlaType());
        policy.setPriority(dto.getPriority() != null ? dto.getPriority() : 1);
        policy.setResponseTime(dto.getResponseTime());
        policy.setResolutionTime(dto.getResolutionTime());
        policy.setWarningThreshold(dto.getWarningThreshold() != null ? dto.getWarningThreshold() : new BigDecimal("80.00"));
        policy.setEscalationRules(dto.getEscalationRules());
        policy.setNotificationChannels(dto.getNotificationChannels());
        policy.setEnabled(true);
        policy.setDescription(dto.getDescription());

        policyMapper.insert(policy);
        return policy;
    }

    @Transactional
    public SlaPolicy updatePolicy(Long id, SlaDTO.PolicyUpdateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        SlaPolicy policy = policyMapper.selectById(id);
        if (policy == null || !policy.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA策略不存在");
        }

        if (dto.getPolicyName() != null) policy.setPolicyName(dto.getPolicyName());
        if (dto.getSlaType() != null) policy.setSlaType(dto.getSlaType());
        if (dto.getPriority() != null) policy.setPriority(dto.getPriority());
        if (dto.getResponseTime() != null) policy.setResponseTime(dto.getResponseTime());
        if (dto.getResolutionTime() != null) policy.setResolutionTime(dto.getResolutionTime());
        if (dto.getWarningThreshold() != null) policy.setWarningThreshold(dto.getWarningThreshold());
        if (dto.getEscalationRules() != null) policy.setEscalationRules(dto.getEscalationRules());
        if (dto.getNotificationChannels() != null) policy.setNotificationChannels(dto.getNotificationChannels());
        if (dto.getEnabled() != null) policy.setEnabled(dto.getEnabled());
        if (dto.getDescription() != null) policy.setDescription(dto.getDescription());

        policyMapper.updateById(policy);
        return policy;
    }

    public Page<SlaPolicy> listPolicies(int page, int size, String slaType, Boolean enabled) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<SlaPolicy> wrapper = new LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getTenantId, tenantId);

        if (slaType != null) wrapper.eq(SlaPolicy::getSlaType, slaType);
        if (enabled != null) wrapper.eq(SlaPolicy::getEnabled, enabled);

        wrapper.orderByDesc(SlaPolicy::getPriority, SlaPolicy::getCreatedAt);
        return policyMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public SlaPolicy getPolicy(Long id) {
        Long tenantId = TenantContext.getTenantId();
        SlaPolicy policy = policyMapper.selectById(id);
        if (policy == null || !policy.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA策略不存在");
        }
        return policy;
    }

    @Transactional
    public void deletePolicy(Long id) {
        Long tenantId = TenantContext.getTenantId();
        SlaPolicy policy = policyMapper.selectById(id);
        if (policy == null || !policy.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA策略不存在");
        }
        policyMapper.deleteById(id);
    }

    @Transactional
    public SlaRecord createRecord(SlaDTO.RecordCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        SlaPolicy policy = getPolicy(dto.getPolicyId());

        LambdaQueryWrapper<SlaRecord> existingWrapper = new LambdaQueryWrapper<SlaRecord>()
                .eq(SlaRecord::getTenantId, tenantId)
                .eq(SlaRecord::getBusinessType, dto.getBusinessType())
                .eq(SlaRecord::getBusinessId, dto.getBusinessId());
        if (recordMapper.selectCount(existingWrapper) > 0) {
            throw new BusinessException("该业务已存在SLA记录");
        }

        LocalDateTime startTime = dto.getStartTime() != null ? dto.getStartTime() : LocalDateTime.now();

        SlaRecord record = new SlaRecord();
        record.setId(IdUtil.getSnowflakeNextId());
        record.setTenantId(tenantId);
        record.setPolicyId(policy.getId());
        record.setBusinessType(dto.getBusinessType());
        record.setBusinessId(dto.getBusinessId());
        record.setStatus("pending");
        record.setStartTime(startTime);
        record.setResponseDeadline(startTime.plusMinutes(policy.getResponseTime()));
        record.setResolutionDeadline(startTime.plusMinutes(policy.getResolutionTime()));
        record.setCurrentStage("pending");
        record.setEscalationLevel(0);
        record.setNotificationsSent(new ArrayList<>());

        recordMapper.insert(record);
        enrichRecordWithRuntimeData(record, policy);
        return record;
    }

    @Transactional
    public SlaRecord ackResponse(Long recordId, Long operatorId) {
        Long tenantId = TenantContext.getTenantId();
        SlaRecord record = recordMapper.selectById(recordId);
        if (record == null || !record.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA记录不存在");
        }
        if (!"pending".equals(record.getStatus()) && !"in_progress".equals(record.getStatus())) {
            throw new BusinessException("当前状态不支持响应确认");
        }

        record.setResponseTime(LocalDateTime.now());
        record.setStatus("in_progress");
        record.setCurrentStage("processing");
        recordMapper.updateById(record);

        SlaPolicy policy = policyMapper.selectById(record.getPolicyId());
        enrichRecordWithRuntimeData(record, policy);
        return record;
    }

    @Transactional
    public SlaRecord ackResolution(Long recordId, Long operatorId) {
        Long tenantId = TenantContext.getTenantId();
        SlaRecord record = recordMapper.selectById(recordId);
        if (record == null || !record.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA记录不存在");
        }
        if (!"in_progress".equals(record.getStatus())) {
            throw new BusinessException("当前状态不支持解决确认");
        }

        record.setResolutionTime(LocalDateTime.now());
        record.setStatus("completed");
        record.setCurrentStage("completed");
        recordMapper.updateById(record);

        SlaPolicy policy = policyMapper.selectById(record.getPolicyId());
        enrichRecordWithRuntimeData(record, policy);
        return record;
    }

    public SlaRecord getRecord(Long id) {
        Long tenantId = TenantContext.getTenantId();
        SlaRecord record = recordMapper.selectById(id);
        if (record == null || !record.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA记录不存在");
        }
        SlaPolicy policy = policyMapper.selectById(record.getPolicyId());
        enrichRecordWithRuntimeData(record, policy);
        return record;
    }

    public Page<SlaRecord> listRecords(int page, int size, String status, String businessType) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<SlaRecord> wrapper = new LambdaQueryWrapper<SlaRecord>()
                .eq(SlaRecord::getTenantId, tenantId);

        if (status != null) wrapper.eq(SlaRecord::getStatus, status);
        if (businessType != null) wrapper.eq(SlaRecord::getBusinessType, businessType);

        wrapper.orderByDesc(SlaRecord::getCreatedAt);
        Page<SlaRecord> pageResult = recordMapper.selectPage(new Page<>(page, size), wrapper);

        Map<Long, SlaPolicy> policyMap = getPolicyMap(pageResult.getRecords());
        pageResult.getRecords().forEach(record -> enrichRecordWithRuntimeData(record, policyMap.get(record.getPolicyId())));

        return pageResult;
    }

    public SlaDTO.SlaSummaryDTO getSummary() {
        Long tenantId = TenantContext.getTenantId();
        SlaDTO.SlaSummaryDTO summary = new SlaDTO.SlaSummaryDTO();

        LambdaQueryWrapper<SlaRecord> wrapper = new LambdaQueryWrapper<SlaRecord>()
                .eq(SlaRecord::getTenantId, tenantId);

        List<SlaRecord> allRecords = recordMapper.selectList(wrapper);
        summary.setTotalRecords((long) allRecords.size());
        summary.setPendingCount(allRecords.stream().filter(r -> "pending".equals(r.getStatus())).count());
        summary.setInProgressCount(allRecords.stream().filter(r -> "in_progress".equals(r.getStatus())).count());
        summary.setCompletedCount(allRecords.stream().filter(r -> "completed".equals(r.getStatus())).count());
        summary.setBreachedCount(allRecords.stream().filter(r -> "breached".equals(r.getStatus())).count());

        Map<Long, SlaPolicy> policyMap = getPolicyMap(allRecords);
        long warningCount = 0;
        long onTimeCount = 0;
        long completedWithResolution = 0;
        long totalResponseTime = 0;
        long totalResolutionTime = 0;

        for (SlaRecord record : allRecords) {
            enrichRecordWithRuntimeData(record, policyMap.get(record.getPolicyId()));
            if (Boolean.TRUE.equals(record.getIsWarning())) warningCount++;
            if (record.getResponseTime() != null) {
                Duration duration = Duration.between(record.getStartTime(), record.getResponseTime());
                totalResponseTime += duration.toMinutes();
                completedWithResolution++;
            }
            if (record.getResolutionTime() != null) {
                Duration duration = Duration.between(record.getStartTime(), record.getResolutionTime());
                totalResolutionTime += duration.toMinutes();
                SlaPolicy policy = policyMap.get(record.getPolicyId());
                if (policy != null && duration.toMinutes() <= policy.getResolutionTime()) {
                    onTimeCount++;
                }
            }
        }

        summary.setWarningCount(warningCount);
        if (completedWithResolution > 0) {
            summary.setAverageResponseTime(BigDecimal.valueOf(totalResponseTime).divide(BigDecimal.valueOf(completedWithResolution), 2, RoundingMode.HALF_UP));
            summary.setAverageResolutionTime(BigDecimal.valueOf(totalResolutionTime).divide(BigDecimal.valueOf(completedWithResolution), 2, RoundingMode.HALF_UP));
        } else {
            summary.setAverageResponseTime(BigDecimal.ZERO);
            summary.setAverageResolutionTime(BigDecimal.ZERO);
        }

        long totalCompleted = summary.getCompletedCount() + summary.getBreachedCount();
        if (totalCompleted > 0) {
            summary.setOnTimeRate(BigDecimal.valueOf(onTimeCount).divide(BigDecimal.valueOf(totalCompleted), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
        } else {
            summary.setOnTimeRate(BigDecimal.valueOf(100));
        }

        return summary;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void monitorSlaDeadlines() {
        log.info("Starting SLA deadline monitoring task at {}", LocalDateTime.now());
        try {
            List<SlaRecord> activeRecords = recordMapper.selectList(
                    new LambdaQueryWrapper<SlaRecord>()
                            .in(SlaRecord::getStatus, "pending", "in_progress")
            );

            Map<Long, List<SlaRecord>> tenantRecords = activeRecords.stream()
                    .collect(Collectors.groupingBy(SlaRecord::getTenantId));

            for (Map.Entry<Long, List<SlaRecord>> entry : tenantRecords.entrySet()) {
                TenantContext.setTenantId(entry.getKey());
                try {
                    processTenantSlaRecords(entry.getValue());
                } finally {
                    TenantContext.clear();
                }
            }

            log.info("SLA monitoring completed, processed {} records from {} tenants",
                    activeRecords.size(), tenantRecords.size());
        } catch (Exception e) {
            log.error("SLA monitoring task failed", e);
        }
    }

    @Transactional
    public void processTenantSlaRecords(List<SlaRecord> records) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, SlaPolicy> policyMap = getPolicyMap(records);

        for (SlaRecord record : records) {
            SlaPolicy policy = policyMap.get(record.getPolicyId());
            if (policy == null) continue;

            enrichRecordWithRuntimeData(record, policy);

            if (Boolean.TRUE.equals(record.getIsBreached()) && !"breached".equals(record.getStatus())) {
                record.setStatus("breached");
                recordMapper.updateById(record);
                createEscalation(record, policy, "resolution_breach", now);
                continue;
            }

            if (Boolean.TRUE.equals(record.getIsWarning())) {
                checkAndTriggerEscalation(record, policy, now);
            }
        }
    }

    private void checkAndTriggerEscalation(SlaRecord record, SlaPolicy policy, LocalDateTime now) {
        BigDecimal warningThreshold = policy.getWarningThreshold() != null ? policy.getWarningThreshold() : new BigDecimal("80.00");
        BigDecimal progress = record.getResponseProgress() != null ? record.getResponseProgress() : BigDecimal.ZERO;

        if (progress.compareTo(warningThreshold) >= 0 && record.getEscalationLevel() == 0) {
            createEscalation(record, policy, "warning", now);
            record.setEscalationLevel(1);
            record.setLastEscalationAt(now);
            recordMapper.updateById(record);
        }

        List<Map<String, Object>> rules = (List<Map<String, Object>>) policy.getEscalationRules().get("rules");
        if (rules != null) {
            for (Map<String, Object> rule : rules) {
                Integer level = (Integer) rule.get("level");
                Integer thresholdMinutes = (Integer) rule.get("thresholdMinutes");
                if (level != null && thresholdMinutes != null && level > record.getEscalationLevel()) {
                    Duration elapsed = Duration.between(record.getStartTime(), now);
                    if (elapsed.toMinutes() >= thresholdMinutes) {
                        createEscalation(record, policy, (String) rule.get("type"), now);
                        record.setEscalationLevel(level);
                        record.setLastEscalationAt(now);
                        recordMapper.updateById(record);
                        break;
                    }
                }
            }
        }
    }

    private void createEscalation(SlaRecord record, SlaPolicy policy, String type, LocalDateTime now) {
        SlaEscalation escalation = new SlaEscalation();
        escalation.setId(IdUtil.getSnowflakeNextId());
        escalation.setTenantId(record.getTenantId());
        escalation.setSlaRecordId(record.getId());
        escalation.setEscalationLevel(record.getEscalationLevel() + 1);
        escalation.setEscalationType(type);
        escalation.setEscalationTime(now);

        List<Map<String, Object>> rules = (List<Map<String, Object>>) policy.getEscalationRules().get("rules");
        if (rules != null) {
            for (Map<String, Object> rule : rules) {
                if (type.equals(rule.get("type"))) {
                    escalation.setNotifiedUsers((List<Long>) rule.get("notifiedUsers"));
                    break;
                }
            }
        }

        escalation.setNotificationChannels(policy.getNotificationChannels());
        escalation.setAcknowledged(false);

        escalationMapper.insert(escalation);

        List<Map<String, Object>> notifications = record.getNotificationsSent();
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", type);
        notification.put("level", escalation.getEscalationLevel());
        notification.put("time", now.toString());
        notification.put("escalationId", escalation.getId());
        notifications.add(notification);
        record.setNotificationsSent(notifications);

        sendNotification(escalation, record, policy);
    }

    private void sendNotification(SlaEscalation escalation, SlaRecord record, SlaPolicy policy) {
        log.info("Sending SLA {} notification for record {} to users: {}, channels: {}",
                escalation.getEscalationType(), record.getId(),
                escalation.getNotifiedUsers(), escalation.getNotificationChannels());
    }

    private void enrichRecordWithRuntimeData(SlaRecord record, SlaPolicy policy) {
        if (policy == null) return;

        LocalDateTime now = LocalDateTime.now();

        if (record.getResponseTime() == null && record.getResponseDeadline() != null) {
            Duration remaining = Duration.between(now, record.getResponseDeadline());
            record.setRemainingResponseMinutes(Math.max(0, remaining.toMinutes()));

            Duration total = Duration.ofMinutes(policy.getResponseTime());
            Duration elapsed = Duration.between(record.getStartTime(), now);
            BigDecimal progress = BigDecimal.valueOf(elapsed.toMinutes())
                    .divide(BigDecimal.valueOf(total.toMinutes()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            record.setResponseProgress(progress.min(new BigDecimal("100.00")));
        } else if (record.getResponseTime() != null) {
            record.setRemainingResponseMinutes(0L);
            record.setResponseProgress(new BigDecimal("100.00"));
        }

        if (record.getResolutionTime() == null && record.getResolutionDeadline() != null) {
            Duration remaining = Duration.between(now, record.getResolutionDeadline());
            record.setRemainingResolutionMinutes(Math.max(0, remaining.toMinutes()));

            Duration total = Duration.ofMinutes(policy.getResolutionTime());
            Duration elapsed = Duration.between(record.getStartTime(), now);
            BigDecimal progress = BigDecimal.valueOf(elapsed.toMinutes())
                    .divide(BigDecimal.valueOf(total.toMinutes()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            record.setResolutionProgress(progress.min(new BigDecimal("100.00")));
        } else if (record.getResolutionTime() != null) {
            record.setRemainingResolutionMinutes(0L);
            record.setResolutionProgress(new BigDecimal("100.00"));
        }

        BigDecimal warningThreshold = policy.getWarningThreshold() != null ? policy.getWarningThreshold() : new BigDecimal("80.00");
        boolean isResponseWarning = record.getResponseProgress() != null && record.getResponseProgress().compareTo(warningThreshold) >= 0;
        boolean isResolutionWarning = record.getResolutionProgress() != null && record.getResolutionProgress().compareTo(warningThreshold) >= 0;
        record.setIsWarning(isResponseWarning || isResolutionWarning);

        boolean isResponseBreached = record.getResponseDeadline() != null && now.isAfter(record.getResponseDeadline()) && record.getResponseTime() == null;
        boolean isResolutionBreached = record.getResolutionDeadline() != null && now.isAfter(record.getResolutionDeadline()) && record.getResolutionTime() == null;
        record.setIsBreached(isResponseBreached || isResolutionBreached);
    }

    private Map<Long, SlaPolicy> getPolicyMap(List<SlaRecord> records) {
        Set<Long> policyIds = records.stream()
                .map(SlaRecord::getPolicyId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (policyIds.isEmpty()) return Collections.emptyMap();

        List<SlaPolicy> policies = policyMapper.selectBatchIds(policyIds);
        return policies.stream().collect(Collectors.toMap(SlaPolicy::getId, p -> p));
    }

    private void validatePolicyCreate(SlaDTO.PolicyCreateDTO dto, Long tenantId) {
        if (dto.getPolicyCode() == null || dto.getPolicyCode().trim().isEmpty()) {
            throw new ValidationException("策略编码不能为空");
        }
        if (dto.getResponseTime() == null || dto.getResponseTime() <= 0) {
            throw new ValidationException("响应时限必须大于0");
        }
        if (dto.getResolutionTime() == null || dto.getResolutionTime() <= 0) {
            throw new ValidationException("解决时限必须大于0");
        }
        if (dto.getResolutionTime() <= dto.getResponseTime()) {
            throw new ValidationException("解决时限必须大于响应时限");
        }

        LambdaQueryWrapper<SlaPolicy> wrapper = new LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getTenantId, tenantId)
                .eq(SlaPolicy::getPolicyCode, dto.getPolicyCode());
        if (policyMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("策略编码已存在");
        }
    }

    public List<SlaEscalation> getRecordEscalations(Long recordId) {
        Long tenantId = TenantContext.getTenantId();
        SlaRecord record = recordMapper.selectById(recordId);
        if (record == null || !record.getTenantId().equals(tenantId)) {
            throw new BusinessException("SLA记录不存在");
        }

        return escalationMapper.selectList(
                new LambdaQueryWrapper<SlaEscalation>()
                        .eq(SlaEscalation::getSlaRecordId, recordId)
                        .orderByDesc(SlaEscalation::getEscalationTime)
        );
    }

    @Transactional
    public SlaEscalation ackEscalation(Long escalationId, Long operatorId) {
        Long tenantId = TenantContext.getTenantId();
        SlaEscalation escalation = escalationMapper.selectById(escalationId);
        if (escalation == null || !escalation.getTenantId().equals(tenantId)) {
            throw new BusinessException("升级记录不存在");
        }

        escalation.setAcknowledged(true);
        escalation.setAcknowledgedBy(operatorId);
        escalation.setAcknowledgedAt(LocalDateTime.now());
        escalationMapper.updateById(escalation);

        return escalation;
    }
}
