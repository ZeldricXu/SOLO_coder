package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.MetricAggregate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricAggregateMapper extends BaseMapper<MetricAggregate> {
}
