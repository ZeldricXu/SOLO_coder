package com.tsdbproxy.quality.service;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.common.entity.QualityResult;
import com.tsdbproxy.common.entity.QualityRule;
import com.tsdbproxy.common.mapper.QualityResultMapper;
import com.tsdbproxy.common.mapper.QualityRuleMapper;
import com.tsdbproxy.quality.dto.QualityCheckRequest;
import com.tsdbproxy.quality.dto.QualityCheckResult;
import com.tsdbproxy.quality.dto.QualityRuleCreateRequest;
import com.tsdbproxy.quality.rules.QualityRuleChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCheckService {

    private final QualityRuleMapper qualityRuleMapper;
    private final QualityResultMapper qualityResultMapper;
    private final Map<String, QualityRuleChecker> ruleMap;

    public QualityCheckService(QualityRuleMapper qualityRuleMapper, QualityResultMapper qualityResultMapper,
                        List<QualityRuleChecker> rules) {
        this.qualityRuleMapper = qualityRuleMapper;
        this.qualityResultMapper = qualityResultMapper;
        this.ruleMap = rules.stream()
                .collect(Collectors.toMap(QualityRuleChecker::getRuleType, r -> r));
    }

    public Mono<QualityRule> createRule(QualityRuleCreateRequest request) {
        return Mono.fromCallable(() -> {
            QualityRule rule = new QualityRule();
            rule.setName(request.getName());
            rule.setRuleType(request.getRuleType());
            rule.setDatasourceId(request.getDatasourceId());
            rule.setTableName(request.getTableName());
            rule.setColumnName(request.getColumnName());
            rule.setRuleConfig(JSONUtil.toJsonStr(request.getRuleConfig()));
            rule.setSeverity(request.getSeverity());
            rule.setCronExpression(request.getCronExpression());
            rule.setEnabled(request.getEnabled());
            qualityRuleMapper.insert(rule);
            return rule;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<QualityCheckResult> check(QualityCheckRequest request) {
        return Mono.fromCallable(() -> {
            QualityRule rule = qualityRuleMapper.selectById(request.getRuleId());
            if (rule == null) {
                throw new IllegalArgumentException("规则不存在");
            }

            QualityRuleChecker qualityRule = ruleMap.get(rule.getRuleType());
            if (qualityRule == null) {
                throw new IllegalArgumentException("不支持的规则类型: " + rule.getRuleType());
            }

            QualityCheckResult result = qualityRule.check(rule, null);

            QualityResult entity = new QualityResult();
            entity.setRuleId(result.getRuleId());
            entity.setCheckTime(result.getCheckTime());
            entity.setStatus(result.getStatus());
            entity.setActualValue(result.getActualValue());
            entity.setExpectedValue(result.getExpectedValue());
            entity.setErrorMessage(result.getErrorMessage());
            entity.setAbnormalDataCount(result.getAbnormalDataCount());
            qualityResultMapper.insert(entity);

            rule.setLastCheckTime(LocalDateTime.now());
            qualityRuleMapper.updateById(rule);

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Scheduled(cron = "0 0 * * * ?")
    public void scheduledCheck() {
        log.info("开始定时质量检查任务执行");

        Flux.fromIterable(qualityRuleMapper.selectList(null))
                .filter(rule -> rule.getEnabled() == 1)
                .flatMap(rule -> {
                    QualityCheckRequest request = new QualityCheckRequest();
                    request.setRuleId(rule.getId());
                    return check(request);
                })
                .subscribe();
    }
}
