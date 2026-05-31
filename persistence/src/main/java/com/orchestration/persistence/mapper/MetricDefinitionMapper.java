package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.MetricDefinition;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricDefinitionMapper extends BaseMapper<MetricDefinition> {
}
