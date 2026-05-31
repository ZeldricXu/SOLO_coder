package com.llmgateway.evaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.evaluation.entity.EvaluationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface EvaluationRunMapper extends BaseMapper<EvaluationRun> {

    @Select("SELECT * FROM evaluation_run WHERE model_id = #{modelId} AND deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<EvaluationRun> findByModelId(@Param("modelId") String modelId, @Param("limit") Integer limit);
}
