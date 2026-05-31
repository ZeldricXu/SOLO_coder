package com.monitoring.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitoring.persistence.entity.AlertRuleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRuleDO> {
}
