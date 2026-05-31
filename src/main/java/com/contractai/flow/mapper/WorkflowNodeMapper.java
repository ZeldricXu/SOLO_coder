package com.contractai.flow.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.flow.entity.WorkflowNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {
}
