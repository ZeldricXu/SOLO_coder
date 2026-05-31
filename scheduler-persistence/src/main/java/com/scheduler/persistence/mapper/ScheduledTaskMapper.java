package com.scheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.scheduler.persistence.entity.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;
import java.util.List;

@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

    @Select("SELECT * FROM scheduled_tasks WHERE status = 'ACTIVE' AND next_execution_time <= #{now}")
    List<ScheduledTask> findTasksToExecute(@Param("now") Instant now);

    @Select("SELECT * FROM scheduled_tasks WHERE namespace = #{namespace}")
    IPage<ScheduledTask> findByNamespace(Page<ScheduledTask> page, @Param("namespace") String namespace);

    @Select("SELECT * FROM scheduled_tasks WHERE task_type = #{taskType}")
    List<ScheduledTask> findByTaskType(@Param("taskType") String taskType);

    @Select("SELECT * FROM scheduled_tasks WHERE status = #{status}")
    List<ScheduledTask> findByStatus(@Param("status") String status);
}
