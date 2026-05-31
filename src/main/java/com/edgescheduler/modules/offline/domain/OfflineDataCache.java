package com.edgescheduler.modules.offline.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("offline_data_cache")
public class OfflineDataCache extends BaseEntity {

    @TableField("cache_id")
    private String cacheId;

    @TableField("device_id")
    private String deviceId;

    @TableField("data_type")
    private String dataType;

    @TableField(value = "data_content", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> dataContent;

    @TableField("data_size")
    private Long dataSize;

    @TableField("cache_time")
    private LocalDateTime cacheTime;

    @TableField("sync_status")
    private String syncStatus;

    @TableField("sync_attempts")
    private Integer syncAttempts;

    @TableField("last_sync_attempt")
    private LocalDateTime lastSyncAttempt;

    @TableField("sync_time")
    private LocalDateTime syncTime;

    @TableField("sync_result")
    private String syncResult;

    @TableField("error_detail")
    private String errorDetail;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("priority")
    private Integer priority;
}
