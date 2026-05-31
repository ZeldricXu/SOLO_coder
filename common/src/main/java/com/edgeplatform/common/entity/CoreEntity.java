package com.edgeplatform.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "core_entity", autoResultMap = true)
public class CoreEntity extends BaseEntity {

    private String entityId;

    private String type;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;
}
