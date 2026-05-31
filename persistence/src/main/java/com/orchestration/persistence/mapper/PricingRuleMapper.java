package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.PricingRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PricingRuleMapper extends BaseMapper<PricingRule> {
}
