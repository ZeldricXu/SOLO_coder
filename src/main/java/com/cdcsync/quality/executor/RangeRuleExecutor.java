package com.cdcsync.quality.executor;

import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.service.DataSourceService;
import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.domain.QualityRule;
import com.cdcsync.quality.enums.RuleType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Slf4j
@Component
public class RangeRuleExecutor extends AbstractRuleExecutor {

    public RangeRuleExecutor(DataSourceService dataSourceService) {
        super(dataSourceService);
    }

    @Override
    public boolean supports(String ruleType) {
        return RuleType.RANGE.name().equals(ruleType);
    }

    @Override
    public QualityCheckResult execute(QualityRule rule) {
        DataSource dataSource = dataSourceService.findById(rule.getDataSourceId());
        if (dataSource == null) {
            return createErrorResult(rule, new RuntimeException("DataSource not found: " + rule.getDataSourceId()));
        }

        String[] range = rule.getRuleExpression().split(",");
        if (range.length != 2) {
            return createErrorResult(rule, new RuntimeException("Invalid range format, expected: min,max"));
        }

        String min = range[0].trim();
        String max = range[1].trim();

        String sql = String.format(
                "SELECT COUNT(*) FROM %s WHERE %s < ? OR %s > ?",
                rule.getTableName(),
                rule.getColumnName(),
                rule.getColumnName()
        );

        try (Connection conn = getConnection(dataSource);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, min);
            stmt.setObject(2, max);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long outOfRangeCount = rs.getLong(1);
                    if (outOfRangeCount == 0) {
                        return createSuccessResult(rule, "0");
                    } else {
                        String sampleSql = String.format(
                                "SELECT %s FROM %s WHERE %s < ? OR %s > ? LIMIT 5",
                                rule.getColumnName(),
                                rule.getTableName(),
                                rule.getColumnName(),
                                rule.getColumnName()
                        );
                        try (PreparedStatement sampleStmt = conn.prepareStatement(sampleSql)) {
                            sampleStmt.setObject(1, min);
                            sampleStmt.setObject(2, max);
                            try (ResultSet sampleRs = sampleStmt.executeQuery()) {
                                StringBuilder sampleData = new StringBuilder();
                                while (sampleRs.next()) {
                                    sampleData.append(sampleRs.getString(1)).append(",");
                                }
                                return createFailResult(rule, String.valueOf(outOfRangeCount),
                                        "Found " + outOfRangeCount + " values out of range [" + min + ", " + max + "]",
                                        sampleData.toString());
                            }
                        }
                    }
                }
                return createErrorResult(rule, new RuntimeException("No result returned"));
            }
        } catch (Exception e) {
            log.error("Failed to execute RANGE rule: {}", rule.getName(), e);
            return createErrorResult(rule, e);
        }
    }
}
