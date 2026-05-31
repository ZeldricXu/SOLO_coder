package com.datastandard.modules.quality.checker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class NullCheckRuleChecker implements RuleChecker {

    @Override
    public List<Map<String, Object>> check(Map<String, Object> data) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() == null) {
                Map<String, Object> v = new HashMap<>();
                v.put("field", entry.getKey());
                v.put("type", "NULL_VALUE");
                v.put("severity", "ERROR");
                issues.add(v);
            }
        }
        return issues;
    }

    @Override
    public String getRuleName() {
        return "null_check";
    }
}
