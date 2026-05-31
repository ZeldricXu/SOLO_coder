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
@TableName(value = "stage_transition", autoResultMap = true)
public class StageTransition extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String transitionId;

    private String modelId;

    private String version;

    private String fromStage;

    private String toStage;

    private String reason;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> approvalChecklist;

    private String transitionedBy;

    private LocalDateTime transitionedAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> rollbackInfo;

    private String status;

    private String rollbackFromTransitionId;
}
