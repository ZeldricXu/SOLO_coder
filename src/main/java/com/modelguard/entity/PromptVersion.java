package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "prompt_version", autoResultMap = true)
public class PromptVersion extends BaseEntity {

    @TableField("prompt_id")
    private String promptId;

    @TableField("version")
    private Integer version;

    @TableField("content")
    private String content;

    @TableField(value = "variables", typeHandler = JacksonTypeHandler.class)
    private ObjectNode variables;

    @TableField("created_by")
    private String createdBy;

    @TableField("description")
    private String description;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
