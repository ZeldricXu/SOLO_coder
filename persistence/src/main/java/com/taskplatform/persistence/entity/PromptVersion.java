package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prompt_versions")
public class PromptVersion extends BaseEntity {

    @TableField("prompt_id")
    private String promptId;

    @TableField("version")
    private Integer version;

    @TableField("name")
    private String name;

    @TableField("content")
    private String content;

    @TableField("template")
    private String template;

    @TableField("variables")
    private String variables;

    @TableField("model_id")
    private String modelId;

    @TableField("temperature")
    private Double temperature;

    @TableField("max_tokens")
    private Integer maxTokens;

    @TableField("description")
    private String description;

    @TableField("tags")
    private String tags;

    @TableField("created_by")
    private String createdBy;

    @TableField("is_latest")
    private Boolean isLatest;
}
