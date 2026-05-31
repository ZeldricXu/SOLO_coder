package com.contractai.flow.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.flow.entity.WorkflowInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {
}
