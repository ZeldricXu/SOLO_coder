package com.taskflow.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.data.entity.RunInstanceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RunInstanceMapper extends BaseMapper<RunInstanceEntity> {

    @Select("SELECT * FROM run_instance WHERE tenant_id = #{tenantId} AND run_id = #{runId}")
    RunInstanceEntity selectByTenantAndId(@Param("tenantId") String tenantId, @Param("runId") String runId);

    @Select("SELECT * FROM run_instance WHERE tenant_id = #{tenantId} AND entity_id = #{entityId} ORDER BY started_at DESC LIMIT #{limit}")
    List<RunInstanceEntity> selectByEntityId(@Param("tenantId") String tenantId, @Param("entityId") String entityId, @Param("limit") int limit);

    @Select("SELECT * FROM run_instance WHERE tenant_id = #{tenantId} AND phase = #{phase}")
    List<RunInstanceEntity> selectByPhase(@Param("tenantId") String tenantId, @Param("phase") String phase);

    @Update("UPDATE run_instance SET phase = #{phase}, progress = #{progress}, updated_at = #{updatedAt} WHERE run_id = #{runId}")
    int updateProgress(@Param("runId") String runId, @Param("phase") String phase,
                       @Param("progress") Double progress, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE run_instance SET phase = 'completed', progress = 1.0, completed_at = #{completedAt}, updated_at = #{completedAt} WHERE run_id = #{runId}")
    int markCompleted(@Param("runId") String runId, @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE run_instance SET phase = 'failed', progress = #{progress}, error_detail = #{errorDetail}, completed_at = #{completedAt}, updated_at = #{completedAt} WHERE run_id = #{runId}")
    int markFailed(@Param("runId") String runId, @Param("progress") Double progress,
                   @Param("errorDetail") String errorDetail, @Param("completedAt") LocalDateTime completedAt);
}
