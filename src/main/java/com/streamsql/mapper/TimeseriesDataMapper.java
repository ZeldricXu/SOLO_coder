package com.streamsql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.streamsql.entity.TimeseriesData;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TimeseriesDataMapper extends BaseMapper<TimeseriesData> {
}
