package com.datastandard.modules.quality.checker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConsistencyCheckRuleChecker implements RuleChecker {

    @Override
    public List<Map<String, Object>> check(Map<String, Object> data) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (data.containsKey("startTime") && data.containsKey("endTime")) {
            Object startTime = data.get("startTime");
            Object endTime = data.get("endTime");
            if (startTime instanceof Comparable && endTime instanceof Comparable) {
                @SuppressWarnings("unchecked")
                Comparable<Object> start = (Comparable<Object>) startTime;
                @SuppressWarnings("unchecked")
                Comparable<Object> end = (Comparable<Object>) endTime;
                if (start.compareTo(end) > 0) {
                    Map<String, Object> v = new HashMap<>();
                    v.put("field", "startTime/endTime");
                    v.put("type", "INCONSISTENT_TIME");
                    v.put("severity", "WARNING");
                    issues.add(v);
                }
            }
        }
        return issues;
    }

    @Override
    public String getRuleName() {
        return "consistency_check";
    }
}
