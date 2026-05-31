package com.llmgateway.modelregistry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.modelregistry.entity.ModelEndpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ModelEndpointMapper extends BaseMapper<ModelEndpoint> {

    @Select("SELECT * FROM model_endpoint WHERE model_id = #{modelId} AND deleted = 0 ORDER BY priority DESC, weight DESC")
    List<ModelEndpoint> selectByModelId(@Param("modelId") String modelId);

    @Select("SELECT * FROM model_endpoint WHERE provider = #{provider} AND status = 'active' AND deleted = 0")
    List<ModelEndpoint> selectByProvider(@Param("provider") String provider);
}
