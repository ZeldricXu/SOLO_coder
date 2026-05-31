package com.edgescheduler.cache.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class OfflineCacheDataDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String cacheId;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    private String dataType;
    private String source;
    private Map<String, Object> payload;
    private Long payloadSize;
    private String status;
    private LocalDateTime cachedAt;
    private LocalDateTime syncedAt;
    private Integer syncAttempts;
    private String lastSyncError;
    private Integer priority;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
