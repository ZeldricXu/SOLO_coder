package com.edgescheduler.inference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.inference.entity.AiModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {

    @Select("SELECT * FROM ai_model WHERE model_id = #{modelId}")
    AiModel selectByModelId(@Param("modelId") String modelId);
}
