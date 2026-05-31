package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gpu_node", autoResultMap = true)
public class GpuNode extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String nodeId;

    private String hostname;

    private String ipAddress;

    private Integer gpuCount;

    private String gpuModel;

    private Integer totalGpuMemoryGb;

    private Integer availableGpuMemoryGb;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> labels;

    private LocalDateTime lastHeartbeat;
}
