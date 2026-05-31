package com.llmgateway.gpu.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("gpu_node")
public class GpuNode implements Serializable {

    @TableId(value = "node_id", type = IdType.INPUT)
    private String nodeId;

    @TableField("node_name")
    private String nodeName;

    @TableField("host")
    private String host;

    @TableField("port")
    private Integer port;

    @TableField("gpu_type")
    private String gpuType;

    @TableField("gpu_count")
    private Integer gpuCount;

    @TableField("total_memory_gb")
    private Integer totalMemoryGb;

    @TableField("available_memory_gb")
    private Integer availableMemoryGb;

    @TableField("status")
    private String status;

    @TableField(value = "labels", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, String> labels;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
