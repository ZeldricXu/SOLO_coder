package com.datastandard.modules.quality.checker;

import com.datastandard.modules.quality.rule.QualityRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class DataQualityChecker {

    private final QualityRule qualityRule;
    private final Map<String, RuleChecker> ruleCheckers;

    public DataQualityChecker(QualityRule qualityRule,
                              List<RuleChecker> ruleCheckers) {
        this.qualityRule = qualityRule;
        this.ruleCheckers = new HashMap<>();
        for (RuleChecker checker : ruleCheckers) {
            this.ruleCheckers.put(checker.getRuleName(), checker);
        }
    }

    public Map<String, Object> check(Map<String, Object> data) {
        log.info("开始数据质量检查");

        Map<String, Object> result = initializeResult(data);
        List<Map<String, Object>> issues = new ArrayList<>();

        if (!qualityRule.validate(data)) {
            issues.add(qualityRule.getLastViolation());
        }

        issues.addAll(checkNullValues(data));

        return buildResult(result, issues);
    }

    public Map<String, Object> batchCheck(List<Map<String, Object>> records) {
        log.info("批量数据质量检查: {} 条记录", records.size());

        Map<String, Object> result = new HashMap<>();
        result.put("checkTime", System.currentTimeMillis());
        result.put("totalRecords", records.size());

        List<Map<String, Object>> allIssues = new ArrayList<>();
        int passCount = 0;

        for (Map<String, Object> record : records) {
            Map<String, Object> checkResult = check(record);
            List<Map<String, Object>> violations =
                    (List<Map<String, Object>>) checkResult.get("violations");
            if (violations.isEmpty()) {
                passCount++;
            }
            allIssues.addAll(violations);
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRecords", records.size());
        stats.put("passCount", passCount);
        stats.put("failCount", records.size() - passCount);
        stats.put("passRate", (double) passCount / records.size() * 100);
        stats.put("totalViolations", allIssues.size());

        result.put("stats", stats);
        result.put("violations", allIssues);

        return result;
    }

    public Map<String, Object> checkWithRules(Map<String, Object> data, List<String> ruleNames) {
        log.info("按指定规则检查数据: {}", ruleNames);

        List<Map<String, Object>> issues = new ArrayList<>();
        for (String ruleName : ruleNames) {
            RuleChecker checker = ruleCheckers.get(ruleName);
            if (checker != null) {
                issues.addAll(checker.check(data));
            } else {
                log.warn("未找到规则: {}", ruleName);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("violations", issues);
        return result;
    }

    private Map<String, Object> initializeResult(Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>();
        result.put("checkTime", System.currentTimeMillis());
        result.put("dataSize", data.size());
        return result;
    }

    private List<Map<String, Object>> checkNullValues(Map<String, Object> data) {
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

    private Map<String, Object> buildResult(Map<String, Object> result, List<Map<String, Object>> issues) {
        List<String> fieldNames = new ArrayList<>(
                ((Map<String, Object>) result.get("dataSize") != null ? new ArrayList<>() :
                        new ArrayList<>()));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFields", fieldNames.size());
        stats.put("violations", issues.size());
        stats.put("passRate", issues.isEmpty() ? 100.0 :
                (1.0 - (double) issues.size() / Math.max(1, fieldNames.size())) * 100);

        result.put("stats", stats);
        result.put("violations", issues);
        return result;
    }
}
