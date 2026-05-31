package com.smartflow.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.persistence.entity.ProcessInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcessInstanceMapper extends BaseMapper<ProcessInstance> {
}
