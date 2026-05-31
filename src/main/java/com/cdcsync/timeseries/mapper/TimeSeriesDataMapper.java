package com.cdcsync.timeseries.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.timeseries.domain.TimeSeriesData;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TimeSeriesDataMapper extends BaseMapper<TimeSeriesData> {
}
