package com.iotplatform.device.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.iotplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_device")
public class SysDevice extends BaseEntity {

    @TableField("device_id")
    private String deviceId;

    @TableField("device_name")
    private String deviceName;

    @TableField("device_type")
    private String deviceType;

    @TableField("protocol_type")
    private String protocolType;

    @TableField("status")
    private String status;

    @TableField("auth_token")
    private String authToken;

    @TableField("auth_secret")
    private String authSecret;

    @TableField("metadata")
    private String metadata;

    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @TableField("activated_at")
    private LocalDateTime activatedAt;

    @TableField("created_by")
    private String createdBy;

    public interface Status {
        String INACTIVE = "inactive";
        String ACTIVE = "active";
        String OFFLINE = "offline";
        String FAULT = "fault";
        String DEACTIVATED = "deactivated";
    }
}
