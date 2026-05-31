package com.dynamiclog.common.entity;

import com.dynamiclog.common.enums.SyncStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class OfflineData extends BaseEntity {
    private String dataType;
    private String dataKey;
    private String payload;
    private String checksum;
    private SyncStatus syncStatus;
    private Integer retryCount = 0;
    private LocalDateTime syncedAt;
    private String syncError;
    private String sourceDevice;
    private Long sizeBytes;
}
