package com.cdcsync.quality.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.quality.domain.QualityCheckResult;
import com.cdcsync.quality.mapper.QualityCheckResultMapper;
import com.cdcsync.quality.service.QualityCheckResultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class QualityCheckResultServiceImpl extends AbstractBaseService<QualityCheckResult, String, QualityCheckResultMapper>
        implements QualityCheckResultService {

    public QualityCheckResultServiceImpl(QualityCheckResultMapper mapper) {
        super(mapper);
    }

    @Override
    protected void setId(QualityCheckResult entity, String id) {
        entity.setId(id);
    }

    @Override
    protected String getId(QualityCheckResult entity) {
        return entity.getId();
    }

    @Override
    public List<QualityCheckResult> findByRuleId(String ruleId) {
        QueryWrapper<QualityCheckResult> wrapper = new QueryWrapper<>();
        wrapper.eq("rule_id", ruleId);
        wrapper.orderByDesc("check_time");
        return mapper.selectList(wrapper);
    }

    @Override
    public PageResult<QualityCheckResult> findByRuleId(String ruleId, int pageNum, int pageSize) {
        QueryWrapper<QualityCheckResult> wrapper = new QueryWrapper<>();
        wrapper.eq("rule_id", ruleId);
        wrapper.orderByDesc("check_time");
        Page<QualityCheckResult> page = mapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }
}
