package com.edgescheduler.modules.aggregation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.aggregation.domain.AggregationCheckpoint;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AggregationCheckpointMapper extends BaseMapper<AggregationCheckpoint> {
}
