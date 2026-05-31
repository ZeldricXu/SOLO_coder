package com.cdcsync.quality.executor;

import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.domain.QualityRule;

public interface RuleExecutor {

    boolean supports(String ruleType);

    QualityCheckResult execute(QualityRule rule);
}
