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
public class NotNullRuleExecutor extends AbstractRuleExecutor {

    public NotNullRuleExecutor(DataSourceService dataSourceService) {
        super(dataSourceService);
    }

    @Override
    public boolean supports(String ruleType) {
        return RuleType.NOT_NULL.name().equals(ruleType);
    }

    @Override
    public QualityCheckResult execute(QualityRule rule) {
        DataSource dataSource = dataSourceService.findById(rule.getDataSourceId());
        if (dataSource == null) {
            return createErrorResult(rule, new RuntimeException("DataSource not found: " + rule.getDataSourceId()));
        }

        String sql = String.format(
                "SELECT COUNT(*) FROM %s WHERE %s IS NULL",
                rule.getTableName(),
                rule.getColumnName()
        );

        try (Connection conn = getConnection(dataSource);
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                long nullCount = rs.getLong(1);
                if (nullCount == 0) {
                    return createSuccessResult(rule, "0");
                } else {
                    String sampleSql = String.format(
                            "SELECT * FROM %s WHERE %s IS NULL LIMIT 5",
                            rule.getTableName(),
                            rule.getColumnName()
                    );
                    try (PreparedStatement sampleStmt = conn.prepareStatement(sampleSql);
                         ResultSet sampleRs = sampleStmt.executeQuery()) {
                        StringBuilder sampleData = new StringBuilder();
                        while (sampleRs.next()) {
                            sampleData.append(sampleRs.getString(1)).append(",");
                        }
                        return createFailResult(rule, String.valueOf(nullCount),
                                "Found " + nullCount + " null values",
                                sampleData.toString());
                    }
                }
            }
            return createErrorResult(rule, new RuntimeException("No result returned"));
        } catch (Exception e) {
            log.error("Failed to execute NOT_NULL rule: {}", rule.getName(), e);
            return createErrorResult(rule, e);
        }
    }
}
