package com.smartflow.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.persistence.entity.ProcessDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcessDefinitionMapper extends BaseMapper<ProcessDefinition> {
}
