package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_gpu_resource")
public class GpuResourceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String gpuId;
    private String name;
    private Integer totalMemoryMb;
    private Integer usedMemoryMb;
    private Integer cudaCores;
    private Double utilizationPercent;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
