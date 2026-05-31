package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "prompt_version", autoResultMap = true)
public class PromptVersion extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String promptId;

    private Integer version;

    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> variables;

    private String description;

    private String createdBy;
}
