package com.tsdbproxy.quality.rules;

import com.tsdbproxy.common.entity.QualityRule;
import com.tsdbproxy.quality.dto.QualityCheckResult;

public interface QualityRuleChecker {

    QualityCheckResult check(QualityRule rule, Object data);

    String getRuleType();
}
