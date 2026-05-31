package com.taskflow.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.data.entity.TaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

    @Select("SELECT * FROM task WHERE tenant_id = #{tenantId} AND task_id = #{taskId}")
    TaskEntity selectByTenantAndId(@Param("tenantId") String tenantId, @Param("taskId") String taskId);

    @Select("SELECT * FROM task WHERE tenant_id = #{tenantId} AND status = 'active'")
    List<TaskEntity> selectActiveTasks(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM task WHERE tenant_id = #{tenantId} AND status = 'active' AND next_run_time <= #{now}")
    List<TaskEntity> selectTasksToRun(@Param("tenantId") String tenantId, @Param("now") LocalDateTime now);

    @Update("UPDATE task SET last_run_time = #{lastRunTime}, next_run_time = #{nextRunTime}, updated_at = #{updatedAt} WHERE task_id = #{taskId}")
    int updateRunTimes(@Param("taskId") String taskId, @Param("lastRunTime") LocalDateTime lastRunTime,
                       @Param("nextRunTime") LocalDateTime nextRunTime, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE task SET status = #{status}, updated_at = #{updatedAt} WHERE task_id = #{taskId}")
    int updateStatus(@Param("taskId") String taskId, @Param("status") String status, @Param("updatedAt") LocalDateTime updatedAt);
}
