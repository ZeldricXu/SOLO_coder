package com.datastandard.modules.quality.report;

import com.datastandard.modules.quality.rule.QualityRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class QualityReporter {

    private final QualityRule qualityRule;

    public QualityReporter(QualityRule qualityRule) {
        this.qualityRule = qualityRule;
    }

    public void report(Map<String, Object> result) {
        log.info("生成数据质量报告");

        Map<String, Object> report = new HashMap<>();
        report.put("reportTime", LocalDateTime.now().toString());
        report.put("result", result);
        report.put("summary", generateSummary(result));

        saveReport(report);
    }

    public void reportBatch(Map<String, Object> result) {
        log.info("生成批量数据质量报告");

        Map<String, Object> report = new HashMap<>();
        report.put("reportTime", LocalDateTime.now().toString());
        report.put("type", "BATCH");
        report.put("result", result);
        report.put("summary", generateBatchSummary(result));

        saveReport(report);
    }

    private Map<String, Object> generateSummary(Map<String, Object> result) {
        Map<String, Object> summary = new HashMap<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) result.get("violations");

        if (violations != null) {
            long errorCount = countBySeverity(violations, "ERROR");
            long warningCount = countBySeverity(violations, "WARNING");

            summary.put("errorCount", errorCount);
            summary.put("warningCount", warningCount);
            summary.put("totalIssues", violations.size());
            summary.put("level", determineLevel(errorCount, warningCount));
        }

        return summary;
    }

    private Map<String, Object> generateBatchSummary(Map<String, Object> result) {
        Map<String, Object> summary = new HashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");

        if (stats != null) {
            Double passRate = (Double) stats.get("passRate");
            if (passRate != null) {
                summary.put("level", determineLevel(passRate));
            }
        }

        return summary;
    }

    private long countBySeverity(List<Map<String, Object>> violations, String severity) {
        return violations.stream()
                .filter(v -> severity.equals(v.get("severity")))
                .count();
    }

    private String determineLevel(long errorCount, long warningCount) {
        if (errorCount > 0) {
            return "RED";
        } else if (warningCount > 0) {
            return "YELLOW";
        } else {
            return "GREEN";
        }
    }

    private String determineLevel(double passRate) {
        if (passRate >= 95) {
            return "GREEN";
        } else if (passRate >= 80) {
            return "YELLOW";
        } else {
            return "RED";
        }
    }

    private void saveReport(Map<String, Object> report) {
        log.info("保存质量报告: {}", report);
    }

    public Map<String, Object> generateSuggestedFixes(Map<String, Object> violation) {
        return qualityRule.generateSuggestions(violation);
    }

    public void exportReport(String format) {
        log.info("导出报告, 格式: {}", format);
    }
}
