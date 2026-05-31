package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_shadow")
public class DeviceShadow extends BaseEntity {
    private String deviceId;
    private String desiredState;
    private String reportedState;
    private String deltaState;
    private Integer version;
    private Instant desiredUpdatedAt;
    private Instant reportedUpdatedAt;
    private String lastSyncError;
    private boolean syncPending;
}
