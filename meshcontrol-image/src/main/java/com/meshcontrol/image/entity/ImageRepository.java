package com.meshcontrol.image.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "image_repository", autoResultMap = true)
public class ImageRepository extends BaseEntity {

    private String repoId;
    private String registryId;
    private String name;
    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private Boolean syncEnabled;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> syncPolicy;

    private LocalDateTime lastSyncAt;
}
