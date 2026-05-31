package com.datastandard.modules.quality.checker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class UniquenessCheckRuleChecker implements RuleChecker {

    @Override
    public List<Map<String, Object>> check(Map<String, Object> data) {
        return new ArrayList<>();
    }

    @Override
    public String getRuleName() {
        return "uniqueness_check";
    }
}
