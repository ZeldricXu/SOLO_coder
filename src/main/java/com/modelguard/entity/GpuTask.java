package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gpu_task", autoResultMap = true)
public class GpuTask extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String taskId;

    private String name;

    private String taskType;

    private Integer priority;

    private Integer requiredGpuCount;

    private Integer requiredGpuMemoryGb;

    private Long estimatedRuntimeMs;

    private String status;

    private String nodeId;

    private String gpuIndices;

    private BigDecimal progress;

    private Boolean preemptible;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private String submittedBy;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> labels;

    private String errorDetail;

    private LocalDateTime submittedAt;

    private LocalDateTime scheduledAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
