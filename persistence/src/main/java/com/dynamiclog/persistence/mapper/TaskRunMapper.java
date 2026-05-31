package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.TaskRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface TaskRunMapper extends BaseMapper<TaskRun> {

    @Select("SELECT * FROM task_run WHERE task_id = #{taskId} AND deleted = 0 ORDER BY started_at DESC LIMIT #{limit}")
    List<TaskRun> findByTaskIdLimit(@Param("taskId") String taskId, @Param("limit") int limit);

    @Select("SELECT * FROM task_run WHERE run_id = #{runId} AND deleted = 0")
    TaskRun findByRunId(@Param("runId") String runId);
}
