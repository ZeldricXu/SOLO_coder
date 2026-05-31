package com.chainetl.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.common.model.ConfigDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigDefinitionMapper extends BaseMapper<ConfigDefinition> {
}
