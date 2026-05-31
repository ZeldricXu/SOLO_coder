package com.edgescheduler.inference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.inference.entity.InferenceTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InferenceTaskMapper extends BaseMapper<InferenceTask> {

    @Select("SELECT * FROM inference_task WHERE task_id = #{taskId}")
    InferenceTask selectByTaskId(@Param("taskId") String taskId);

    @Select("SELECT * FROM inference_task WHERE status = #{status} ORDER BY " +
            "CASE priority WHEN 'critical' THEN 1 WHEN 'high' THEN 2 WHEN 'normal' THEN 3 ELSE 4 END, " +
            "created_at ASC LIMIT #{limit}")
    List<InferenceTask> selectPendingTasks(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM inference_task WHERE device_key = #{deviceKey} ORDER BY created_at DESC LIMIT #{limit}")
    List<InferenceTask> selectByDeviceKey(@Param("deviceKey") String deviceKey, @Param("limit") int limit);
}
