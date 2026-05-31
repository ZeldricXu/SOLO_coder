package com.monitoring.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitoring.persistence.entity.MetricDataDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricDataMapper extends BaseMapper<MetricDataDO> {
}
