package com.enterprise.gateway.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.gateway.common.model.CircuitBreakerRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CircuitBreakerRuleMapper extends BaseMapper<CircuitBreakerRule> {
}
