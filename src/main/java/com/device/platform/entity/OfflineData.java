package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("offline_data")
public class OfflineData extends BaseEntity {
    private String dataId;
    private String deviceId;
    private String dataType;
    private String payload;
    private Instant collectedAt;
    private boolean synced;
    private Instant syncedAt;
    private Integer syncAttempts;
    private String syncError;
    private String checksum;
}
