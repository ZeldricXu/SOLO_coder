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
@TableName("t_experiment")
public class ExperimentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String experimentId;
    private String name;
    private String description;
    private String promptId;
    private String status;
    private String variantsJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    @TableLogic
    private Integer deleted;
}
