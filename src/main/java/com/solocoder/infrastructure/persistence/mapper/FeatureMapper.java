package com.solocoder.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solocoder.infrastructure.persistence.entity.FeatureEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FeatureMapper extends BaseMapper<FeatureEntity> {

    @Select("SELECT * FROM features WHERE entity_id = #{entityId} AND feature_name = #{featureName} " +
            "AND event_time BETWEEN #{startTime} AND #{endTime} ORDER BY event_time")
    List<FeatureEntity> findByEntityAndFeatureAndTimeRange(
            @Param("entityId") String entityId,
            @Param("featureName") String featureName,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
