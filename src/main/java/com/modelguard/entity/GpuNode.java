package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gpu_node", autoResultMap = true)
public class GpuNode extends BaseEntity {

    @TableField("node_id")
    private String nodeId;

    @TableField("hostname")
    private String hostname;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("gpu_count")
    private Integer gpuCount;

    @TableField("gpu_model")
    private String gpuModel;

    @TableField("total_gpu_memory_gb")
    private BigDecimal totalGpuMemoryGb;

    @TableField("available_gpu_memory_gb")
    private BigDecimal availableGpuMemoryGb;

    @TableField("status")
    private String status;

    @TableField(value = "labels", typeHandler = JacksonTypeHandler.class)
    private ObjectNode labels;

    @TableField("last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
