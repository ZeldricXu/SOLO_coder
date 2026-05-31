package com.edgescheduler.modules.device.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import com.edgescheduler.domain.enums.DeviceStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_info")
public class DeviceInfo extends BaseEntity {

    @TableField("device_id")
    private String deviceId;

    @TableField("device_name")
    private String deviceName;

    @TableField("device_type")
    private String deviceType;

    @TableField("device_model")
    private String deviceModel;

    @TableField("manufacturer")
    private String manufacturer;

    @TableField("serial_number")
    private String serialNumber;

    @TableField("firmware_version")
    private String firmwareVersion;

    @TableField("hardware_version")
    private String hardwareVersion;

    @TableField("device_secret")
    private String deviceSecret;

    @TableField("device_cert")
    private String deviceCert;

    @TableField("status")
    private DeviceStatus status;

    @TableField(value = "device_labels", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> deviceLabels;

    @TableField(value = "device_config", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> deviceConfig;

    @TableField("activated_at")
    private LocalDateTime activatedAt;

    @TableField("last_online_time")
    private LocalDateTime lastOnlineTime;

    @TableField("last_heartbeat_time")
    private LocalDateTime lastHeartbeatTime;

    @TableField("heartbeat_interval")
    private Integer heartbeatInterval;

    @TableField("ip_address")
    private String ipAddress;

    @TableField("location")
    private String location;

    @TableField("auth_method")
    private String authMethod;
}
