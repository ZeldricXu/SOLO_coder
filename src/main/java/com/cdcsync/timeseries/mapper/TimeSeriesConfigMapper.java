package com.cdcsync.timeseries.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.timeseries.domain.TimeSeriesConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TimeSeriesConfigMapper extends BaseMapper<TimeSeriesConfig> {
}
