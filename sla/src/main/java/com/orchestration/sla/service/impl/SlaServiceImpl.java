package com.orchestration.sla.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.persistence.entity.SlaPolicy;
import com.orchestration.persistence.entity.SlaRecord;
import com.orchestration.persistence.entity.TaskInstance;
import com.orchestration.persistence.mapper.SlaPolicyMapper;
import com.orchestration.persistence.mapper.SlaRecordMapper;
import com.orchestration.persistence.mapper.TaskInstanceMapper;
import com.orchestration.sla.service.SlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlaServiceImpl implements SlaService {

    private final SlaPolicyMapper slaPolicyMapper;
    private final SlaRecordMapper slaRecordMapper;
    private final TaskInstanceMapper taskInstanceMapper;

    @Override
    public Long createPolicy(SlaPolicy policy) {
        slaPolicyMapper.insert(policy);
        return policy.getId();
    }

    @Override
    public boolean updatePolicy(SlaPolicy policy) {
        return slaPolicyMapper.updateById(policy) > 0;
    }

    @Override
    public SlaPolicy getPolicy(Long id) {
        return slaPolicyMapper.selectById(id);
    }

    @Override
    public List<SlaPolicy> listPolicies(Integer page, Integer size) {
        Page<SlaPolicy> pageResult = slaPolicyMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<SlaPolicy>().orderByDesc(SlaPolicy::getCreatedAt)
        );
        return pageResult.getRecords();
    }

    @Override
    public boolean deletePolicy(Long id) {
        return slaPolicyMapper.deleteById(id) > 0;
    }

    @Override
    public Long createSlaRecord(Long taskInstanceId, Long policyId) {
        TaskInstance task = taskInstanceMapper.selectById(taskInstanceId);
        if (task == null) {
            throw new BusinessException("任务实例不存在");
        }

        SlaPolicy policy = slaPolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new BusinessException("SLA策略不存在");
        }

        LocalDateTime startTime = task.getStartedAt() != null ? task.getStartedAt() : LocalDateTime.now();
        LocalDateTime endTime = startTime.plusNanos(policy.getSlaDuration() * 1_000_000);

        SlaRecord record = new SlaRecord();
        record.setTaskInstanceId(taskInstanceId);
        record.setPolicyId(policyId);
        record.setSlaStartTime(startTime);
        record.setSlaEndTime(endTime);
        record.setSlaStatus("normal");
        record.setCurrentLevel(0);
        slaRecordMapper.insert(record);
        return record.getId();
    }

    @Override
    public SlaRecord getSlaRecord(Long taskInstanceId) {
        return slaRecordMapper.selectOne(
                new LambdaQueryWrapper<SlaRecord>().eq(SlaRecord::getTaskInstanceId, taskInstanceId)
        );
    }

    @Override
    public List<SlaRecord> listOvertimeRecords(Integer page, Integer size) {
        Page<SlaRecord> pageResult = slaRecordMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<SlaRecord>()
                        .in(SlaRecord::getSlaStatus, "warning", "overtime")
                        .orderByDesc(SlaRecord::getCreatedAt)
        );
        return pageResult.getRecords();
    }

    @Override
    @Scheduled(fixedRate = 60000)
    public void checkAndEscalateSla() {
        log.info("开始检查SLA超时情况");
        List<SlaRecord> records = slaRecordMapper.selectList(
                new LambdaQueryWrapper<SlaRecord>()
                        .in(SlaRecord::getSlaStatus, "normal", "warning")
        );

        LocalDateTime now = LocalDateTime.now();
        for (SlaRecord record : records) {
            try {
                updateSlaStatus(record, now);
            } catch (Exception e) {
                log.error("检查SLA记录失败: {}", record.getId(), e);
            }
        }
        log.info("SLA超时检查完成，处理记录数: {}", records.size());
    }

    private void updateSlaStatus(SlaRecord record, LocalDateTime now) {
        SlaPolicy policy = slaPolicyMapper.selectById(record.getPolicyId());
        if (policy == null) {
            return;
        }

        Duration totalDuration = Duration.between(record.getSlaStartTime(), record.getSlaEndTime());
        Duration elapsed = Duration.between(record.getSlaStartTime(), now);
        double progress = (double) elapsed.toMillis() / totalDuration.toMillis();

        BigDecimal warningThreshold = policy.getWarningThreshold() != null ? policy.getWarningThreshold() : new BigDecimal("0.8");

        if (now.isAfter(record.getSlaEndTime()) && !"overtime".equals(record.getSlaStatus())) {
            record.setSlaStatus("overtime");
            escalate(record, policy);
        } else if (progress >= warningThreshold.doubleValue() && "normal".equals(record.getSlaStatus())) {
            record.setSlaStatus("warning");
            record.setWarningTime(now);
            notifyEscalation(record.getId(), 1);
        }

        slaRecordMapper.updateById(record);
    }

    private void escalate(SlaRecord record, SlaPolicy policy) {
        int nextLevel = record.getCurrentLevel() + 1;
        record.setCurrentLevel(nextLevel);

        List<Map<String, Object>> levels = policy.getEscalationLevels() != null
                ? JsonUtil.fromJson(policy.getEscalationLevels(), List.class)
                : Collections.emptyList();

        if (nextLevel <= levels.size()) {
            notifyEscalation(record.getId(), nextLevel);
        }

        List<Map<String, Object>> history = record.getEscalationHistory() != null
                ? JsonUtil.fromJson(record.getEscalationHistory(), List.class)
                : new ArrayList<>();

        Map<String, Object> escalationEvent = new HashMap<>();
        escalationEvent.put("level", nextLevel);
        escalationEvent.put("time", LocalDateTime.now().toString());
        escalationEvent.put("status", "notified");
        history.add(escalationEvent);

        record.setEscalationHistory(JsonUtil.toJson(history));
    }

    @Override
    public Map<String, Object> calculateRemainingTime(Long recordId) {
        SlaRecord record = slaRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("SLA记录不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        Duration remaining = Duration.between(now, record.getSlaEndTime());

        Map<String, Object> result = new HashMap<>();
        result.put("remainingMs", remaining.toMillis());
        result.put("remainingMinutes", remaining.toMinutes());
        result.put("isOvertime", remaining.isNegative());
        result.put("remainingText", formatDuration(remaining.abs()));
        return result;
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%d小时%d分%d秒", hours, minutes, seconds);
    }

    @Override
    public boolean notifyEscalation(Long recordId, Integer level) {
        log.info("发送SLA升级通知, recordId: {}, level: {}", recordId, level);
        return true;
    }
}
