package com.tsdbproxy.quality.rules.impl;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.common.entity.QualityRule;
import com.tsdbproxy.quality.dto.QualityCheckResult;
import com.tsdbproxy.quality.rules.QualityRuleChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class RangeCheckRule implements QualityRuleChecker {

    @Override
    @SuppressWarnings("unchecked")
    public QualityCheckResult check(QualityRule rule, Object data) {
        QualityCheckResult result = new QualityCheckResult();
        result.setRuleId(rule.getId());
        result.setRuleName(rule.getName());
        result.setCheckTime(LocalDateTime.now());

        try {
            Map<String, Object> config = JSONUtil.toBean(rule.getRuleConfig(), Map.class);
            String columnName = rule.getColumnName();
            Double min = ((Number) config.getOrDefault("min", Double.NEGATIVE_INFINITY)).doubleValue();
            Double max = ((Number) config.getOrDefault("max", Double.POSITIVE_INFINITY)).doubleValue();

            if (data instanceof Map map) {
                Object value = map.get(columnName);
                if (value instanceof Number num) {
                    double d = num.doubleValue();
                    result.setActualValue(String.valueOf(d));
                    result.setExpectedValue("[" + min + ", " + max + "]");
                    if (d < min || d > max) {
                        result.setStatus("fail");
                        result.setAbnormalDataCount(1L);
                        result.setErrorMessage("字段 [" + columnName + "] 值 " + d + " 超出范围 [" + min + ", " + max + "]");
                    } else {
                        result.setStatus("pass");
                    }
                }
            }
        } catch (Exception e) {
            log.error("范围检查失败", e);
            result.setStatus("fail");
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    @Override
    public String getRuleType() {
        return "range_check";
    }
}
