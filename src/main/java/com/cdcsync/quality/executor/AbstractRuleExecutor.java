package com.cdcsync.quality.executor;

import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.service.DataSourceService;
import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.domain.QualityRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractRuleExecutor implements RuleExecutor {

    protected final DataSourceService dataSourceService;

    protected Connection getConnection(DataSource dataSource) throws Exception {
        return DriverManager.getConnection(
                dataSource.getUrl(),
                dataSource.getUsername(),
                dataSource.getPassword()
        );
    }

    protected QualityCheckResult createSuccessResult(QualityRule rule, String actualValue) {
        QualityCheckResult result = new QualityCheckResult();
        result.setRuleId(rule.getId());
        result.setCheckTime(LocalDateTime.now());
        result.setResultStatus("PASS");
        result.setActualValue(actualValue);
        result.setExpectedValue(rule.getExpectedValue());
        return result;
    }

    protected QualityCheckResult createFailResult(QualityRule rule, String actualValue, String errorMessage, String sampleData) {
        QualityCheckResult result = new QualityCheckResult();
        result.setRuleId(rule.getId());
        result.setCheckTime(LocalDateTime.now());
        result.setResultStatus("FAIL");
        result.setActualValue(rule.getExpectedValue());
        result.setExpectedValue(rule.getExpectedValue());
        result.setErrorMessage(errorMessage);
        result.setSampleData(sampleData);
        return result;
    }

    protected QualityCheckResult createErrorResult(QualityRule rule, Exception e) {
        QualityCheckResult result = new QualityCheckResult();
        result.setRuleId(rule.getId());
        result.setCheckTime(LocalDateTime.now());
        result.setResultStatus("ERROR");
        result.setErrorMessage(e.getMessage());
        return result;
    }
}
