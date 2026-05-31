package com.iotplatform.datastream.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iotplatform.datastream.entity.DataAggregation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataAggregationMapper extends BaseMapper<DataAggregation> {

    @Select("SELECT * FROM data_aggregation WHERE device_id = #{deviceId} AND stream_id = #{streamId} " +
            "AND metric_name = #{metricName} AND aggregation_type = #{aggregationType} " +
            "AND window_start >= #{windowStart} AND window_end <= #{windowEnd} " +
            "ORDER BY window_start DESC")
    List<DataAggregation> findByWindow(@Param("deviceId") String deviceId,
                                       @Param("streamId") String streamId,
                                       @Param("metricName") String metricName,
                                       @Param("aggregationType") String aggregationType,
                                       @Param("windowStart") LocalDateTime windowStart,
                                       @Param("windowEnd") LocalDateTime windowEnd);

    @Select("SELECT * FROM data_aggregation WHERE uploaded = 0 ORDER BY created_at ASC LIMIT #{limit}")
    List<DataAggregation> findUnuploaded(@Param("limit") int limit);

    @Update("UPDATE data_aggregation SET uploaded = 1, uploaded_at = #{uploadedAt} WHERE id = #{id}")
    int markAsUploaded(@Param("id") Long id, @Param("uploadedAt") LocalDateTime uploadedAt);

    @Select("SELECT * FROM data_aggregation WHERE device_id = #{deviceId} AND stream_id = #{streamId} " +
            "ORDER BY created_at DESC")
    List<DataAggregation> findByDeviceAndStream(@Param("deviceId") String deviceId,
                                                 @Param("streamId") String streamId);

    IPage<DataAggregation> selectAggregationPage(Page<DataAggregation> page,
                                                  @Param("deviceId") String deviceId,
                                                  @Param("streamId") String streamId,
                                                  @Param("metricName") String metricName,
                                                  @Param("aggregationType") String aggregationType,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);
}
