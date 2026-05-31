package com.smartflow.slamonitor.service;

import com.smartflow.common.dto.SlaInfo;
import com.smartflow.common.enums.SlaStatus;
import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.common.utils.JsonUtils;
import com.smartflow.persistence.entity.SlaNotification;
import com.smartflow.persistence.entity.SlaPolicy;
import com.smartflow.persistence.entity.SlaTracking;
import com.smartflow.persistence.mapper.SlaNotificationMapper;
import com.smartflow.persistence.mapper.SlaPolicyMapper;
import com.smartflow.persistence.mapper.SlaTrackingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SlaMonitorService {

    private final SlaPolicyMapper policyMapper;
    private final SlaTrackingMapper trackingMapper;
    private final SlaNotificationMapper notificationMapper;

    @Transactional
    public SlaPolicy createPolicy(SlaPolicy policy) {
        SlaPolicy existing = policyMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getPolicyCode, policy.getPolicyCode())
        );
        if (existing != null) {
            throw new BusinessException("SLA策略编码已存在");
        }

        policy.setId(IdGenerator.generateId());
        policy.setEnabled(1);
        policyMapper.insert(policy);
        return policy;
    }

    public SlaPolicy getPolicy(Long policyId) {
        SlaPolicy policy = policyMapper.selectById(policyId);
        if (policy == null) {
            throw new BusinessException("SLA策略不存在");
        }
        return policy;
    }

    public List<SlaPolicy> listPolicies(String relatedType) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaPolicy> query =
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getEnabled, 1);

        if (relatedType != null && !relatedType.isEmpty()) {
            query.eq(SlaPolicy::getRelatedType, relatedType);
        }

        return policyMapper.selectList(query);
    }

    @Transactional
    public SlaTracking startTracking(String policyCode, Long relatedId, String relatedType, Map<String, Object> relatedData) {
        SlaPolicy policy = policyMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaPolicy>()
                .eq(SlaPolicy::getPolicyCode, policyCode)
                .eq(SlaPolicy::getEnabled, 1)
        );
        if (policy == null) {
            throw new BusinessException("SLA策略不存在或未启用");
        }

        SlaTracking existing = trackingMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaTracking>()
                .eq(SlaTracking::getRelatedId, relatedId)
                .eq(SlaTracking::getRelatedType, relatedType)
                .in(SlaTracking::getSlaStatus, Arrays.asList(0, 1))
        );
        if (existing != null) {
            throw new BusinessException("该业务已存在SLA追踪");
        }

        LocalDateTime startTime = LocalDateTime.now();
        LocalDateTime deadline = startTime.plusSeconds(policy.getResolutionTime());
        LocalDateTime warningTime = startTime.plusSeconds(policy.getWarningTime() != null ?
            policy.getWarningTime() : policy.getResolutionTime() * 4 / 5);

        SlaTracking tracking = new SlaTracking();
        tracking.setId(IdGenerator.generateId());
        tracking.setPolicyId(policy.getId());
        tracking.setPolicyName(policy.getPolicyName());
        tracking.setRelatedId(relatedId);
        tracking.setRelatedType(relatedType);
        tracking.setSlaStatus(SlaStatus.NORMAL.getCode());
        tracking.setStartTime(startTime);
        tracking.setDeadline(deadline);
        tracking.setWarningTime(warningTime);
        tracking.setRemainingTime(Duration.between(startTime, deadline).getSeconds());
        tracking.setEscalationLevel(0);
        tracking.setRelatedData(JsonUtils.toJson(relatedData));
        trackingMapper.insert(tracking);

        return tracking;
    }

    public SlaInfo getSlaInfo(Long trackingId) {
        SlaTracking tracking = trackingMapper.selectById(trackingId);
        if (tracking == null) {
            throw new BusinessException("SLA追踪不存在");
        }

        return buildSlaInfo(tracking);
    }

    public SlaInfo getSlaInfoByRelated(Long relatedId, String relatedType) {
        SlaTracking tracking = trackingMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaTracking>()
                .eq(SlaTracking::getRelatedId, relatedId)
                .eq(SlaTracking::getRelatedType, relatedType)
                .in(SlaTracking::getSlaStatus, Arrays.asList(0, 1))
        );
        if (tracking == null) {
            return null;
        }

        return buildSlaInfo(tracking);
    }

    private SlaInfo buildSlaInfo(SlaTracking tracking) {
        SlaInfo info = new SlaInfo();
        info.setSlaId(tracking.getId());
        info.setRelatedId(tracking.getRelatedId());
        info.setRelatedType(tracking.getRelatedType());
        info.setDeadline(tracking.getDeadline());
        info.setSlaStatus(tracking.getSlaStatus());
        info.setEscalationLevel(tracking.getEscalationLevel());

        if (tracking.getSlaStatus() == 2) {
            info.setRemainingTime(0L);
        } else {
            long remaining = Duration.between(LocalDateTime.now(), tracking.getDeadline()).getSeconds();
            info.setRemainingTime(Math.max(0, remaining));
        }

        return info;
    }

    @Transactional
    public boolean pauseTracking(Long trackingId) {
        SlaTracking tracking = trackingMapper.selectById(trackingId);
        if (tracking == null) {
            throw new BusinessException("SLA追踪不存在");
        }
        tracking.setSlaStatus(SlaStatus.NORMAL.getCode());
        trackingMapper.updateById(tracking);
        return true;
    }

    @Transactional
    public boolean completeTracking(Long trackingId) {
        SlaTracking tracking = trackingMapper.selectById(trackingId);
        if (tracking == null) {
            throw new BusinessException("SLA追踪不存在");
        }
        tracking.setSlaStatus(SlaStatus.NORMAL.getCode());
        tracking.setRemainingTime(0L);
        trackingMapper.updateById(tracking);
        return true;
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkSlaStatus() {
        List<SlaTracking> activeTrackings = trackingMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaTracking>()
                .in(SlaTracking::getSlaStatus, Arrays.asList(0, 1))
        );

        LocalDateTime now = LocalDateTime.now();

        for (SlaTracking tracking : activeTrackings) {
            long remainingSeconds = Duration.between(now, tracking.getDeadline()).getSeconds();
            tracking.setRemainingTime(Math.max(0, remainingSeconds));

            if (remainingSeconds <= 0 && tracking.getSlaStatus() != SlaStatus.OVERDUE.getCode()) {
                tracking.setSlaStatus(SlaStatus.OVERDUE.getCode());
                processEscalation(tracking, 3);
            } else if (tracking.getWarningTime() != null && now.isAfter(tracking.getWarningTime())
                    && tracking.getSlaStatus() == SlaStatus.NORMAL.getCode()) {
                tracking.setSlaStatus(SlaStatus.WARNING.getCode());
                processEscalation(tracking, 1);
            }

            trackingMapper.updateById(tracking);
        }
    }

    private void processEscalation(SlaTracking tracking, Integer notificationType) {
        SlaPolicy policy = policyMapper.selectById(tracking.getPolicyId());
        if (policy == null) {
            return;
        }

        int newLevel = tracking.getEscalationLevel() + 1;
        if (newLevel > policy.getEscalationLevel()) {
            return;
        }

        tracking.setEscalationLevel(newLevel);
        tracking.setLastEscalatedAt(LocalDateTime.now());

        List<Long> escalationRecipients = getEscalationRecipients(tracking, newLevel);
        for (Long recipientId : escalationRecipients) {
            createNotification(tracking, recipientId, notificationType);
        }

        List<Map<String, Object>> escalationHistory = tracking.getEscalationHistory() != null ?
            JsonUtils.parseList(tracking.getEscalationHistory(), Map.class) : new ArrayList<>();

        Map<String, Object> escalationRecord = new HashMap<>();
        escalationRecord.put("level", newLevel);
        escalationRecord.put("time", LocalDateTime.now());
        escalationRecord.put("recipients", escalationRecipients);
        escalationHistory.add(escalationRecord);

        tracking.setEscalationHistory(JsonUtils.toJson(escalationHistory));
    }

    private List<Long> getEscalationRecipients(SlaTracking tracking, int level) {
        List<Long> recipients = new ArrayList<>();
        Map<String, Object> relatedData = tracking.getRelatedData() != null ?
            JsonUtils.parseMap(tracking.getRelatedData()) : Collections.emptyMap();

        if (level == 1) {
            Object assigneeId = relatedData.get("assigneeId");
            if (assigneeId != null) {
                recipients.add(Long.valueOf(assigneeId.toString()));
            }
        } else if (level == 2) {
            Object departmentHeadId = relatedData.get("departmentHeadId");
            if (departmentHeadId != null) {
                recipients.add(Long.valueOf(departmentHeadId.toString()));
            }
        } else if (level >= 3) {
            Object managerId = relatedData.get("managerId");
            if (managerId != null) {
                recipients.add(Long.valueOf(managerId.toString()));
            }
        }

        if (recipients.isEmpty()) {
            recipients.add(1L);
        }

        return recipients;
    }

    private void createNotification(SlaTracking tracking, Long recipientId, Integer notificationType) {
        SlaNotification notification = new SlaNotification();
        notification.setId(IdGenerator.generateId());
        notification.setTrackingId(tracking.getId());
        notification.setRelatedId(tracking.getRelatedId());
        notification.setRelatedType(tracking.getRelatedType());
        notification.setNotificationType(notificationType);
        notification.setRecipientId(recipientId);
        notification.setRecipientName("用户" + recipientId);
        notification.setContent(generateNotificationContent(tracking, notificationType));
        notification.setStatus(0);
        notificationMapper.insert(notification);
    }

    private String generateNotificationContent(SlaTracking tracking, Integer notificationType) {
        StringBuilder content = new StringBuilder();
        switch (notificationType) {
            case 1:
                content.append("【SLA预警】");
                content.append(tracking.getRelatedType()).append(" #").append(tracking.getRelatedId());
                content.append(" 即将超时，请及时处理。");
                content.append(" 截止时间: ").append(tracking.getDeadline());
                break;
            case 2:
                content.append("【SLA超时】");
                content.append(tracking.getRelatedType()).append(" #").append(tracking.getRelatedId());
                content.append(" 已超时，请注意！");
                break;
            case 3:
                content.append("【SLA升级】");
                content.append(tracking.getRelatedType()).append(" #").append(tracking.getRelatedId());
                content.append(" 已升级处理。");
                break;
            default:
                content.append("【SLA通知】");
                break;
        }
        return content.toString();
    }

    public List<SlaNotification> getNotifications(Long trackingId, Integer status) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaNotification> query =
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaNotification>()
                .orderByDesc(SlaNotification::getCreatedAt);

        if (trackingId != null) {
            query.eq(SlaNotification::getTrackingId, trackingId);
        }
        if (status != null) {
            query.eq(SlaNotification::getStatus, status);
        }

        return notificationMapper.selectList(query);
    }

    @Transactional
    public boolean markNotificationSent(Long notificationId) {
        SlaNotification notification = notificationMapper.selectById(notificationId);
        if (notification == null) {
            throw new BusinessException("通知不存在");
        }
        notification.setStatus(1);
        notification.setSentAt(LocalDateTime.now());
        notificationMapper.updateById(notification);
        return true;
    }

    public Map<String, Object> getSlaStatistics(String relatedType, LocalDateTime startTime, LocalDateTime endTime) {
        List<SlaTracking> trackings = trackingMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SlaTracking>()
                .eq(relatedType != null, SlaTracking::getRelatedType, relatedType)
                .between(startTime != null && endTime != null, SlaTracking::getStartTime, startTime, endTime)
        );

        long total = trackings.size();
        long onTime = trackings.stream().filter(t -> t.getSlaStatus() == SlaStatus.NORMAL.getCode()).count();
        long warning = trackings.stream().filter(t -> t.getSlaStatus() == SlaStatus.WARNING.getCode()).count();
        long overdue = trackings.stream().filter(t -> t.getSlaStatus() == SlaStatus.OVERDUE.getCode()).count();

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("onTime", onTime);
        result.put("warning", warning);
        result.put("overdue", overdue);
        result.put("onTimeRate", total > 0 ? String.format("%.2f%%", (double) onTime / total * 100) : "0%");
        result.put("overdueRate", total > 0 ? String.format("%.2f%%", (double) overdue / total * 100) : "0%");

        return result;
    }
}
