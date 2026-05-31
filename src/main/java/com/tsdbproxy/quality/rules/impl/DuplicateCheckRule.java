package com.tsdbproxy.quality.rules.impl;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.common.entity.QualityRule;
import com.tsdbproxy.quality.dto.QualityCheckResult;
import com.tsdbproxy.quality.rules.QualityRuleChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class DuplicateCheckRule implements QualityRuleChecker {

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

            if (data instanceof List list) {
                Set<Object> uniqueValues = new HashSet<>();
                long duplicateCount = 0;

                for (Object item : list) {
                    if (item instanceof Map map) {
                        Object value = map.get(columnName);
                        if (!uniqueValues.add(value)) {
                            duplicateCount++;
                        }
                    }
                }

                result.setActualValue(String.valueOf(duplicateCount));
                result.setExpectedValue("0");

                if (duplicateCount > 0) {
                    result.setStatus("fail");
                    result.setAbnormalDataCount(duplicateCount);
                    result.setErrorMessage("字段 [" + columnName + "] 存在 " + duplicateCount + " 条重复数据");
                } else {
                    result.setStatus("pass");
                }
            }
        } catch (Exception e) {
            log.error("重复检查失败", e);
            result.setStatus("fail");
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    @Override
    public String getRuleType() {
        return "duplicate_check";
    }
}
