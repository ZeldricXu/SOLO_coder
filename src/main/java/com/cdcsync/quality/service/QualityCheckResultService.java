package com.cdcsync.quality.service;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.service.BaseService;
import com.cdcsync.quality.domain.QualityCheckResult;

import java.util.List;

public interface QualityCheckResultService extends BaseService<QualityCheckResult, String> {

    List<QualityCheckResult> findByRuleId(String ruleId);

    PageResult<QualityCheckResult> findByRuleId(String ruleId, int pageNum, int pageSize);
}
