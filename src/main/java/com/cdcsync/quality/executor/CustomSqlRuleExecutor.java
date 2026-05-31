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
import java.sql.ResultSetMetaData;

@Slf4j
@Component
public class CustomSqlRuleExecutor extends AbstractRuleExecutor {

    public CustomSqlRuleExecutor(DataSourceService dataSourceService) {
        super(dataSourceService);
    }

    @Override
    public boolean supports(String ruleType) {
        return RuleType.CUSTOM_SQL.name().equals(ruleType);
    }

    @Override
    public QualityCheckResult execute(QualityRule rule) {
        DataSource dataSource = dataSourceService.findById(rule.getDataSourceId());
        if (dataSource == null) {
            return createErrorResult(rule, new RuntimeException("DataSource not found: " + rule.getDataSourceId()));
        }

        try (Connection conn = getConnection(dataSource);
             PreparedStatement stmt = conn.prepareStatement(rule.getRuleExpression());
             ResultSet rs = stmt.executeQuery()) {

            StringBuilder actualValue = new StringBuilder();
            StringBuilder sampleData = new StringBuilder();

            if (rs.next()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (columnCount > 0) {
                    Object value = rs.getObject(1);
                    actualValue.append(value != null ? value.toString() : "null");
                }

                int rowCount = 0;
                do {
                    if (rowCount < 5) {
                        for (int i = 1; i <= columnCount; i++) {
                            sampleData.append(rs.getObject(i));
                            if (i < columnCount) {
                                sampleData.append("|");
                            }
                        }
                        sampleData.append(";");
                    }
                    rowCount++;
                } while (rs.next());

                boolean pass = false;
                if (rule.getExpectedValue() != null) {
                    try {
                        long expected = Long.parseLong(rule.getExpectedValue());
                        long actual = Long.parseLong(actualValue.toString());
                        pass = actual == expected;
                    } catch (NumberFormatException e) {
                        pass = rule.getExpectedValue().equals(actualValue.toString());
                    }
                }

                if (pass) {
                    return createSuccessResult(rule, actualValue.toString());
                } else {
                    return createFailResult(rule, actualValue.toString(),
                            "Custom SQL validation failed",
                            sampleData.toString());
                }
            }

            return createErrorResult(rule, new RuntimeException("No result returned from custom SQL"));
        } catch (Exception e) {
            log.error("Failed to execute CUSTOM_SQL rule: {}", rule.getName(), e);
            return createErrorResult(rule, e);
        }
    }
}
