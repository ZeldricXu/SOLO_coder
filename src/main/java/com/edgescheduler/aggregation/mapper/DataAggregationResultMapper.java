package com.edgescheduler.aggregation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.aggregation.entity.DataAggregationResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataAggregationResultMapper extends BaseMapper<DataAggregationResult> {

    @Select("SELECT * FROM data_aggregation_result WHERE result_id = #{resultId}")
    DataAggregationResult selectByResultId(@Param("resultId") String resultId);

    @Select("SELECT * FROM data_aggregation_result WHERE stream_id = #{streamId} " +
            "ORDER BY window_start DESC LIMIT #{limit}")
    List<DataAggregationResult> selectByStreamId(@Param("streamId") String streamId,
                                                  @Param("limit") int limit);

    @Select("SELECT * FROM data_aggregation_result WHERE stream_id = #{streamId} " +
            "AND window_start >= #{startTime} AND window_end <= #{endTime} " +
            "ORDER BY window_start ASC")
    List<DataAggregationResult> selectByStreamIdAndTimeRange(
            @Param("streamId") String streamId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Select("SELECT * FROM data_aggregation_result WHERE uploaded = 0 ORDER BY created_at ASC LIMIT #{batchSize}")
    List<DataAggregationResult> selectPendingUpload(@Param("batchSize") int batchSize);

    @Update("UPDATE data_aggregation_result SET uploaded = 1, uploaded_at = #{uploadedAt} " +
            "WHERE result_id = #{resultId}")
    int markAsUploaded(@Param("resultId") String resultId,
                        @Param("uploadedAt") LocalDateTime uploadedAt);
}
