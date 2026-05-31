package com.edgescheduler.modules.rules.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.rules.domain.RuleDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RuleDefinitionMapper extends BaseMapper<RuleDefinition> {
}
