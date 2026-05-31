package com.scheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.scheduler.persistence.entity.TaskExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface TaskExecutionMapper extends BaseMapper<TaskExecution> {

    @Select("SELECT * FROM task_executions WHERE task_id = #{taskId} ORDER BY created_at DESC")
    IPage<TaskExecution> findByTaskId(Page<TaskExecution> page, @Param("taskId") String taskId);

    @Select("SELECT * FROM task_executions WHERE status = #{status}")
    List<TaskExecution> findByStatus(@Param("status") String status);

    @Select("SELECT * FROM task_executions WHERE run_id = #{runId}")
    TaskExecution findByRunId(@Param("runId") String runId);
}
