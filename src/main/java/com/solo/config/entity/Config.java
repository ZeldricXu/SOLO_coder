package com.solo.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Data
@TableName("configs")
public class Config {

    public enum ConfigStatus {
        DRAFT,
        PUBLISHED,
        ARCHIVED
    }

    public static final Map<ConfigStatus, Set<ConfigStatus>> VALID_TRANSITIONS = Map.of(
            ConfigStatus.DRAFT, Set.of(ConfigStatus.PUBLISHED, ConfigStatus.ARCHIVED),
            ConfigStatus.PUBLISHED, Set.of(ConfigStatus.ARCHIVED, ConfigStatus.DRAFT),
            ConfigStatus.ARCHIVED, Set.of(ConfigStatus.DRAFT)
    );

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("config_id")
    private String configId;

    private String namespace;

    private Integer version;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> parameters;

    private Boolean enabled;

    @TableField("status")
    private String status = ConfigStatus.DRAFT.name();

    @TableField("source_type")
    private String sourceType;

    @TableField("applied_at")
    private LocalDateTime appliedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
