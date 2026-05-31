package com.llmgateway.modelregistry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.modelregistry.entity.StageTransitionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface StageTransitionLogMapper extends BaseMapper<StageTransitionLog> {

    @Select("SELECT * FROM stage_transition_log WHERE version_id = #{versionId} ORDER BY created_at DESC")
    List<StageTransitionLog> selectByVersionId(@Param("versionId") String versionId);
}
