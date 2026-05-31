package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.MetricData;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricDataMapper extends BaseMapper<MetricData> {
}
