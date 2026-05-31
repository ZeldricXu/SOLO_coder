package com.llmgateway.gpu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.gpu.entity.GpuTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface GpuTaskMapper extends BaseMapper<GpuTask> {

    @Select("SELECT * FROM gpu_task WHERE status = 'pending' AND deleted = 0 ORDER BY priority DESC, created_at ASC")
    List<GpuTask> findPendingTasks();

    @Select("SELECT * FROM gpu_task WHERE node_id = #{nodeId} AND status IN ('running', 'pending') AND deleted = 0")
    List<GpuTask> findTasksByNode(@Param("nodeId") String nodeId);
}
