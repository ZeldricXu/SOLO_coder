package com.edgescheduler.cache.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "offline_cache_data", autoResultMap = true)
public class OfflineCacheData extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String cacheId;
    private String deviceKey;
    private String dataType;
    private String source;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private Long payloadSize;
    private String status;
    private LocalDateTime cachedAt;
    private LocalDateTime syncedAt;
    private Integer syncAttempts;
    private String lastSyncError;
    private Integer priority;
    private String tags;

    public interface DataType {
        String TELEMETRY = "telemetry";
        String EVENT = "event";
        String ALARM = "alarm";
        String COMMAND_RESPONSE = "command_response";
        String ATTRIBUTE = "attribute";
        String AGGREGATION = "aggregation";
    }

    public interface Status {
        String PENDING = "pending";
        String SYNCING = "syncing";
        String SYNCED = "synced";
        String FAILED = "failed";
        String EXPIRED = "expired";
    }
}
