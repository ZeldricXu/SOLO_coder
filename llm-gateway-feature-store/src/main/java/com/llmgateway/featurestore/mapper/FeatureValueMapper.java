package com.llmgateway.featurestore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.featurestore.entity.FeatureValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeatureValueMapper extends BaseMapper<FeatureValue> {

    @Select("SELECT * FROM feature_value WHERE feature_id = #{featureId} AND entity_key = #{entityKey} " +
            "ORDER BY timestamp_ms DESC LIMIT 1")
    FeatureValue selectLatest(@Param("featureId") String featureId, @Param("entityKey") String entityKey);

    @Select("SELECT * FROM feature_value WHERE feature_id = #{featureId} AND entity_key = #{entityKey} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} ORDER BY timestamp_ms DESC")
    List<FeatureValue> selectRange(@Param("featureId") String featureId,
                                   @Param("entityKey") String entityKey,
                                   @Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime);
}
