package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.TaskDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskDefinitionMapper extends BaseMapper<TaskDefinition> {
}
