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
public class NullCheckRule implements QualityRuleChecker {

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

            if (data instanceof Map map) {
                Object value = map.get(columnName);
                if (value == null || (value instanceof String str && str.trim().isEmpty())) {
                    result.setStatus("fail");
                    result.setExpectedValue("非空");
                    result.setActualValue("null");
                    result.setAbnormalDataCount(1L);
                    result.setErrorMessage("字段 [" + columnName + "] 为空");
                } else {
                    result.setStatus("pass");
                    result.setExpectedValue("非空");
                    result.setActualValue(value.toString());
                }
            }
        } catch (Exception e) {
            log.error("空值检查失败", e);
            result.setStatus("fail");
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    @Override
    public String getRuleType() {
        return "null_check";
    }
}
