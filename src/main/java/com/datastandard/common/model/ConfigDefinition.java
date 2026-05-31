package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datastandard.common.handler.JsonMapTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "config_definitions", autoResultMap = true)
public class ConfigDefinition {

    @TableId(type = IdType.INPUT)
    @TableField("config_id")
    private String configId;

    @TableField("namespace")
    private String namespace;

    @TableField("version")
    private Integer version;

    @TableField(value = "parameters", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> parameters;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("source")
    private String source;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("deleted")
    private Boolean deleted;
}
