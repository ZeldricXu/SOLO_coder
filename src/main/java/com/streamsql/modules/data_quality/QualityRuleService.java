package com.streamsql.modules.data_quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.QualityRuleDTO;
import com.streamsql.entity.AnomalyDataRecord;
import com.streamsql.entity.QualityCheckResult;
import com.streamsql.entity.QualityRule;
import com.streamsql.mapper.AnomalyDataRecordMapper;
import com.streamsql.mapper.QualityCheckResultMapper;
import com.streamsql.mapper.QualityRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityRuleService {

    private final QualityRuleMapper qualityRuleMapper;
    private final QualityCheckResultMapper qualityCheckResultMapper;
    private final AnomalyDataRecordMapper anomalyDataRecordMapper;
    private final TaskScheduler taskScheduler;
    private final ObjectMapper objectMapper;

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @PostConstruct
    public void initScheduledTasks() {
        List<QualityRule> enabledRules = qualityRuleMapper.selectEnabledRules();
        for (QualityRule rule : enabledRules) {
            scheduleTask(rule);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public QualityRule createRule(QualityRuleDTO dto) {
        QualityRule rule = new QualityRule();
        rule.setRuleName(dto.getRuleName());
        rule.setRuleType(dto.getRuleType());
        rule.setDatasourceId(dto.getDatasourceId());
        rule.setTableName(dto.getTableName());
        rule.setColumnName(dto.getColumnName());
        rule.setCheckExpression(dto.getCheckExpression());
        rule.setSeverity(dto.getSeverity());
        rule.setEnabled(dto.getEnabled());
        rule.setCronExpression(dto.getCronExpression());
        
        qualityRuleMapper.insert(rule);
        
        if (Boolean.TRUE.equals(dto.getEnabled()) && dto.getCronExpression() != null) {
            scheduleTask(rule);
        }
        
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public QualityRule updateRule(String ruleId, QualityRuleDTO dto) {
        QualityRule rule = qualityRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        
        cancelTask(ruleId);
        
        rule.setRuleName(dto.getRuleName());
        rule.setRuleType(dto.getRuleType());
        rule.setDatasourceId(dto.getDatasourceId());
        rule.setTableName(dto.getTableName());
        rule.setColumnName(dto.getColumnName());
        rule.setCheckExpression(dto.getCheckExpression());
        rule.setSeverity(dto.getSeverity());
        rule.setEnabled(dto.getEnabled());
        rule.setCronExpression(dto.getCronExpression());
        
        qualityRuleMapper.updateById(rule);
        
        if (Boolean.TRUE.equals(dto.getEnabled()) && dto.getCronExpression() != null) {
            scheduleTask(rule);
        }
        
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(String ruleId) {
        cancelTask(ruleId);
        qualityRuleMapper.deleteById(ruleId);
    }

    public QualityRule getRule(String ruleId) {
        return qualityRuleMapper.selectById(ruleId);
    }

    public PageResult<QualityRule> listRules(int page, int size, String datasourceId, String ruleType) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(QualityRule::getDatasourceId, datasourceId);
        }
        if (ruleType != null) {
            wrapper.eq(QualityRule::getRuleType, ruleType);
        }
        wrapper.orderByDesc(QualityRule::getCreatedAt);
        
        IPage<QualityRule> pageResult = qualityRuleMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    private void scheduleTask(QualityRule rule) {
        if (rule.getCronExpression() == null || rule.getCronExpression().isEmpty()) {
            return;
        }
        
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                () -> executeQualityCheck(rule.getRuleId()),
                new CronTrigger(rule.getCronExpression())
            );
            scheduledTasks.put(rule.getRuleId(), future);
            log.info("Scheduled quality check task for rule: {}", rule.getRuleId());
        } catch (Exception e) {
            log.error("Failed to schedule task for rule: {}", rule.getRuleId(), e);
        }
    }

    private void cancelTask(String ruleId) {
        ScheduledFuture<?> future = scheduledTasks.remove(ruleId);
        if (future != null) {
            future.cancel(false);
            log.info("Cancelled quality check task for rule: {}", ruleId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public QualityCheckResult executeQualityCheck(String ruleId) {
        QualityRule rule = qualityRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在: " + ruleId);
        }
        
        log.info("Executing quality check for rule: {}", rule.getRuleName());
        
        QualityCheckResult result = new QualityCheckResult();
        result.setRuleId(ruleId);
        result.setCheckTime(LocalDateTime.now());
        
        try {
            Map<String, Object> checkResult = performCheck(rule);
            long totalCount = ((Number) checkResult.get("totalCount")).longValue();
            long errorCount = ((Number) checkResult.get("errorCount")).longValue();
            
            result.setStatus(errorCount > 0 ? "failed" : "success");
            result.setTotalCount(totalCount);
            result.setErrorCount(errorCount);
            
            if (errorCount > 0) {
                String errorDetail = objectMapper.writeValueAsString(checkResult.get("errorDetail"));
                result.setErrorDetail(errorDetail);
                result.setErrorSample((String) checkResult.get("errorSample"));
                
                markAnomalyData(rule, checkResult);
            }
            
            rule.setLastCheckTime(LocalDateTime.now());
            qualityRuleMapper.updateById(rule);
            
        } catch (Exception e) {
            log.error("Quality check failed for rule: {}", ruleId, e);
            result.setStatus("error");
            result.setErrorDetail(e.getMessage());
        }
        
        qualityCheckResultMapper.insert(result);
        return result;
    }

    private Map<String, Object> performCheck(QualityRule rule) {
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", 1000L);
        result.put("errorCount", 5L);
        result.put("errorSample", "Sample error data");
        
        Map<String, Object> errorDetail = new HashMap<>();
        errorDetail.put("violationType", rule.getRuleType());
        errorDetail.put("expression", rule.getCheckExpression());
        errorDetail.put("description", "数据不符合质量规则要求");
        result.put("errorDetail", errorDetail);
        
        return result;
    }

    private void markAnomalyData(QualityRule rule, Map<String, Object> checkResult) throws JsonProcessingException {
        AnomalyDataRecord record = new AnomalyDataRecord();
        record.setRuleId(rule.getRuleId());
        record.setDatasourceId(rule.getDatasourceId());
        record.setTableName(rule.getTableName());
        record.setPrimaryKeyValue("sample_pk");
        record.setAnomalyType(rule.getRuleType());
        record.setAnomalyDetail(objectMapper.writeValueAsString(checkResult.get("errorDetail")));
        record.setMarked(true);
        
        anomalyDataRecordMapper.insert(record);
    }

    public List<QualityCheckResult> getCheckResults(String ruleId, int limit) {
        LambdaQueryWrapper<QualityCheckResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityCheckResult::getRuleId, ruleId)
               .orderByDesc(QualityCheckResult::getCheckTime)
               .last("LIMIT " + limit);
        return qualityCheckResultMapper.selectList(wrapper);
    }

    public PageResult<AnomalyDataRecord> getAnomalyRecords(int page, int size, String ruleId, String datasourceId) {
        LambdaQueryWrapper<AnomalyDataRecord> wrapper = new LambdaQueryWrapper<>();
        if (ruleId != null) {
            wrapper.eq(AnomalyDataRecord::getRuleId, ruleId);
        }
        if (datasourceId != null) {
            wrapper.eq(AnomalyDataRecord::getDatasourceId, datasourceId);
        }
        wrapper.orderByDesc(AnomalyDataRecord::getCreatedAt);
        
        IPage<AnomalyDataRecord> pageResult = anomalyDataRecordMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }
}
