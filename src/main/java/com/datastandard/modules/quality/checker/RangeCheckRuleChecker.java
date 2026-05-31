package com.datastandard.modules.quality.checker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class RangeCheckRuleChecker implements RuleChecker {

    @Override
    public List<Map<String, Object>> check(Map<String, Object> data) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() instanceof Number) {
                double value = ((Number) entry.getValue()).doubleValue();
                if (value < 0 || value > Double.MAX_VALUE / 2) {
                    Map<String, Object> v = new HashMap<>();
                    v.put("field", entry.getKey());
                    v.put("value", value);
                    v.put("type", "OUT_OF_RANGE");
                    v.put("severity", "WARNING");
                    issues.add(v);
                }
            }
        }
        return issues;
    }

    @Override
    public String getRuleName() {
        return "range_check";
    }
}
