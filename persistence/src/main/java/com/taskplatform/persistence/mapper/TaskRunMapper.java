package com.taskplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskplatform.persistence.entity.TaskRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskRunMapper extends BaseMapper<TaskRun> {
}
