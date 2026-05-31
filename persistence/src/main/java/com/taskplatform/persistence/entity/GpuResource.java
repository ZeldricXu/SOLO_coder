package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("gpu_resources")
public class GpuResource extends BaseEntity {

    @TableField("gpu_id")
    private String gpuId;

    @TableField("node_name")
    private String nodeName;

    @TableField("gpu_index")
    private Integer gpuIndex;

    @TableField("uuid")
    private String uuid;

    @TableField("model_name")
    private String modelName;

    @TableField("total_memory_mb")
    private Integer totalMemoryMb;

    @TableField("used_memory_mb")
    private Integer usedMemoryMb;

    @TableField("gpu_utilization")
    private Double gpuUtilization;

    @TableField("status")
    private String status;

    @TableField("current_task_id")
    private String currentTaskId;

    @TableField("labels")
    private String labels;

    @TableField("last_heartbeat")
    private java.time.LocalDateTime lastHeartbeat;
}
