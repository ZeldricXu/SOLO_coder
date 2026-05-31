package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.TaskInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskInstanceMapper extends BaseMapper<TaskInstance> {
}
