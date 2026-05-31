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
public class UniqueRuleExecutor extends AbstractRuleExecutor {

    public UniqueRuleExecutor(DataSourceService dataSourceService) {
        super(dataSourceService);
    }

    @Override
    public boolean supports(String ruleType) {
        return RuleType.UNIQUE.name().equals(ruleType);
    }

    @Override
    public QualityCheckResult execute(QualityRule rule) {
        DataSource dataSource = dataSourceService.findById(rule.getDataSourceId());
        if (dataSource == null) {
            return createErrorResult(rule, new RuntimeException("DataSource not found: " + rule.getDataSourceId()));
        }

        String sql = String.format(
                "SELECT COUNT(*) as total, COUNT(DISTINCT %s) as distinct_count FROM %s",
                rule.getColumnName(),
                rule.getTableName()
        );

        try (Connection conn = getConnection(dataSource);
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                long total = rs.getLong("total");
                long distinctCount = rs.getLong("distinct_count");
                long duplicateCount = total - distinctCount;

                if (duplicateCount == 0) {
                    return createSuccessResult(rule, String.valueOf(distinctCount));
                } else {
                    String sampleSql = String.format(
                            "SELECT %s, COUNT(*) as cnt FROM %s GROUP BY %s HAVING COUNT(*) > 1 LIMIT 5",
                            rule.getColumnName(),
                            rule.getTableName(),
                            rule.getColumnName()
                    );
                    try (PreparedStatement sampleStmt = conn.prepareStatement(sampleSql);
                         ResultSet sampleRs = sampleStmt.executeQuery()) {
                        StringBuilder sampleData = new StringBuilder();
                        while (sampleRs.next()) {
                            sampleData.append(sampleRs.getString(1))
                                    .append(":")
                                    .append(sampleRs.getInt("cnt"))
                                    .append(",");
                        }
                        return createFailResult(rule, String.valueOf(duplicateCount),
                                "Found " + duplicateCount + " duplicate values",
                                sampleData.toString());
                    }
                }
            }
            return createErrorResult(rule, new RuntimeException("No result returned"));
        } catch (Exception e) {
            log.error("Failed to execute UNIQUE rule: {}", rule.getName(), e);
            return createErrorResult(rule, e);
        }
    }
}
