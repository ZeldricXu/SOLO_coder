package com.llmgateway.modelregistry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.modelregistry.entity.ModelVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ModelVersionMapper extends BaseMapper<ModelVersion> {

    @Select("SELECT * FROM model_version WHERE model_id = #{modelId} AND deleted = 0 ORDER BY created_at DESC")
    List<ModelVersion> selectByModelId(@Param("modelId") String modelId);

    @Select("SELECT * FROM model_version WHERE model_id = #{modelId} AND stage = #{stage} AND deleted = 0 ORDER BY created_at DESC LIMIT 1")
    ModelVersion selectLatestByStage(@Param("modelId") String modelId, @Param("stage") String stage);
}
