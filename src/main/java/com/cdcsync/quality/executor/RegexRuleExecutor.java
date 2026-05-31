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
public class RegexRuleExecutor extends AbstractRuleExecutor {

    public RegexRuleExecutor(DataSourceService dataSourceService) {
        super(dataSourceService);
    }

    @Override
    public boolean supports(String ruleType) {
        return RuleType.REGEX.name().equals(ruleType);
    }

    @Override
    public QualityCheckResult execute(QualityRule rule) {
        DataSource dataSource = dataSourceService.findById(rule.getDataSourceId());
        if (dataSource == null) {
            return createErrorResult(rule, new RuntimeException("DataSource not found: " + rule.getDataSourceId()));
        }

        String sql = String.format(
                "SELECT COUNT(*) FROM %s WHERE %s NOT REGEXP ?",
                rule.getTableName(),
                rule.getColumnName()
        );

        try (Connection conn = getConnection(dataSource);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, rule.getRuleExpression());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long invalidCount = rs.getLong(1);
                    if (invalidCount == 0) {
                        return createSuccessResult(rule, "0");
                    } else {
                        String sampleSql = String.format(
                                "SELECT %s FROM %s WHERE %s NOT REGEXP ? LIMIT 5",
                                rule.getColumnName(),
                                rule.getTableName(),
                                rule.getColumnName()
                        );
                        try (PreparedStatement sampleStmt = conn.prepareStatement(sampleSql)) {
                            sampleStmt.setString(1, rule.getRuleExpression());
                            try (ResultSet sampleRs = sampleStmt.executeQuery()) {
                                StringBuilder sampleData = new StringBuilder();
                                while (sampleRs.next()) {
                                    sampleData.append(sampleRs.getString(1)).append(",");
                                }
                                return createFailResult(rule, String.valueOf(invalidCount),
                                        "Found " + invalidCount + " values not matching regex: " + rule.getRuleExpression(),
                                        sampleData.toString());
                            }
                        }
                    }
                }
                return createErrorResult(rule, new RuntimeException("No result returned"));
            }
        } catch (Exception e) {
            log.error("Failed to execute REGEX rule: {}", rule.getName(), e);
            return createErrorResult(rule, e);
        }
    }
}
