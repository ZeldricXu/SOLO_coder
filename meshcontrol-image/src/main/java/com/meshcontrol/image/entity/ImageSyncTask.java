package com.meshcontrol.image.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "image_sync_task", autoResultMap = true)
public class ImageSyncTask extends BaseEntity {

    private String taskId;
    private String sourceRegistryId;
    private String targetRegistryId;
    private String sourceRepo;
    private String targetRepo;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tagFilter;

    private String status;
    private Double progress;
    private Integer totalImages;
    private Integer syncedImages;
    private String errorDetail;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
