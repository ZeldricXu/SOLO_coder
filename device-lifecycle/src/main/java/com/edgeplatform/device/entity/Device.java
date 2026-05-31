package com.edgeplatform.device.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgeplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "device", autoResultMap = true)
public class Device extends BaseEntity {

    private String deviceId;

    private String deviceName;

    private String deviceType;

    private String status;

    private String activationCode;

    private LocalDateTime activatedAt;

    private LocalDateTime lastHeartbeatAt;

    private String ipAddress;

    private String firmwareVersion;

    private String hardwareVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> capabilities;

    private String location;

    private String authToken;

    private LocalDateTime authTokenExpiresAt;
}
