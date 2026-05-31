package com.llmgateway.evaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.evaluation.entity.ModelDrift;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ModelDriftMapper extends BaseMapper<ModelDrift> {

    @Select("SELECT * FROM model_drift WHERE model_id = #{modelId} AND created_at BETWEEN #{startTime} AND #{endTime} ORDER BY created_at DESC")
    List<ModelDrift> findByModelIdAndTimeRange(@Param("modelId") String modelId,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    @Select("SELECT * FROM model_drift WHERE is_alert = 1 ORDER BY created_at DESC LIMIT #{limit}")
    List<ModelDrift> findRecentAlerts(@Param("limit") Integer limit);
}
