package com.llmgateway.adversarial.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("adversarial_attack")
public class AdversarialAttack implements Serializable {

    @TableId(value = "attack_id", type = IdType.INPUT)
    private String attackId;

    @TableField("attack_name")
    private String attackName;

    @TableField("attack_type")
    private String attackType;

    @TableField("description")
    private String description;

    @TableField("strategy")
    private String strategy;

    @TableField(value = "parameters", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    @TableField("severity")
    private String severity;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
