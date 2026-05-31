package com.datastandard.modules.quality.checker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class FormatCheckRuleChecker implements RuleChecker {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    @Override
    public List<Map<String, Object>> check(Map<String, Object> data) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey().toLowerCase();
            Object value = entry.getValue();
            if (value != null && key.contains("email")) {
                String strValue = value.toString();
                if (!EMAIL_PATTERN.matcher(strValue).matches()) {
                    Map<String, Object> v = new HashMap<>();
                    v.put("field", entry.getKey());
                    v.put("value", strValue);
                    v.put("type", "INVALID_EMAIL");
                    v.put("severity", "ERROR");
                    issues.add(v);
                }
            }
        }
        return issues;
    }

    @Override
    public String getRuleName() {
        return "format_check";
    }
}
