package com.cdcsync.quality.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.domain.QualityRule;

import java.util.List;

public interface QualityRuleService extends BaseService<QualityRule, String> {

    QualityCheckResult executeRule(String ruleId);

    List<QualityCheckResult> executeAllRules();

    void enableRule(String ruleId);

    void disableRule(String ruleId);
}
