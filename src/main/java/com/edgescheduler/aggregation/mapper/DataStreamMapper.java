package com.edgescheduler.aggregation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.aggregation.entity.DataStream;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DataStreamMapper extends BaseMapper<DataStream> {

    @Select("SELECT * FROM data_stream WHERE stream_id = #{streamId} AND deleted = 0")
    DataStream selectByStreamId(@Param("streamId") String streamId);

    @Select("SELECT * FROM data_stream WHERE device_key = #{deviceKey} AND deleted = 0")
    List<DataStream> selectByDeviceKey(@Param("deviceKey") String deviceKey);

    @Select("SELECT * FROM data_stream WHERE enabled = 1 AND aggregation_type != 'none' AND deleted = 0")
    List<DataStream> selectAllAggregationStreams();
}
