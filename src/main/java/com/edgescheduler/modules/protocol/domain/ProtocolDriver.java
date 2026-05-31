package com.edgescheduler.modules.protocol.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("protocol_driver")
public class ProtocolDriver extends BaseEntity {

    @TableField("driver_id")
    private String driverId;

    @TableField("protocol_type")
    private String protocolType;

    @TableField("driver_name")
    private String driverName;

    @TableField("driver_version")
    private String driverVersion;

    @TableField("driver_class")
    private String driverClass;

    @TableField("driver_path")
    private String driverPath;

    @TableField(value = "connection_params", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> connectionParams;

    @TableField("load_status")
    private String loadStatus;

    @TableField("loaded_at")
    private LocalDateTime loadedAt;

    @TableField("enabled")
    private Boolean enabled;
}
