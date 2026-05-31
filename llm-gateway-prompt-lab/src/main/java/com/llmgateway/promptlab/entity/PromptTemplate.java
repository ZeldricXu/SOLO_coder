package com.llmgateway.promptlab.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("prompt_template")
public class PromptTemplate implements Serializable {

    @TableId(value = "prompt_id", type = IdType.INPUT)
    private String promptId;

    @TableField("prompt_name")
    private String promptName;

    @TableField("description")
    private String description;

    @TableField("template")
    private String template;

    @TableField(value = "variables", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> variables;

    @TableField(value = "model_config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> modelConfig;

    @TableField("version")
    private Integer version;

    @TableField("status")
    private String status;

    @TableField(value = "tags", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, String> tags;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
