package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.Task;
import com.dynamiclog.common.enums.TaskStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    @Select("SELECT * FROM task WHERE status = #{status} AND deleted = 0 ORDER BY priority DESC, created_at ASC")
    List<Task> findByStatus(@Param("status") TaskStatus status);

    @Select("SELECT * FROM task WHERE parent_task_id = #{parentId} AND deleted = 0")
    List<Task> findByParentTaskId(@Param("parentId") String parentId);

    @Select("SELECT * FROM task WHERE status IN ('PENDING','SCHEDULED') AND deleted = 0")
    List<Task> findPendingTasks();
}
