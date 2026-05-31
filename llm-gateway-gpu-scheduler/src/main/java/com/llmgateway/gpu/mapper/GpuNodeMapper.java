package com.llmgateway.gpu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.gpu.entity.GpuNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface GpuNodeMapper extends BaseMapper<GpuNode> {

    @Select("SELECT * FROM gpu_node WHERE status = 'online' AND available_memory_gb >= #{requiredMemory} " +
            "AND gpu_count >= #{requiredGpuCount} AND deleted = 0 ORDER BY available_memory_gb DESC")
    List<GpuNode> findAvailableNodes(@Param("requiredMemory") Integer requiredMemory,
                                     @Param("requiredGpuCount") Integer requiredGpuCount);
}
