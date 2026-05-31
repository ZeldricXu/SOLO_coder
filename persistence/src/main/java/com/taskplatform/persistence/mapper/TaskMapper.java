package com.taskplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskplatform.persistence.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    @Select("SELECT * FROM tasks WHERE status = #{status} ORDER BY priority DESC, created_at ASC LIMIT #{limit}")
    List<Task> findByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM tasks WHERE task_id = #{taskId}")
    Task findByTaskId(@Param("taskId") String taskId);
}
