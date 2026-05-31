package com.edgescheduler.protocol.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "protocol_driver", autoResultMap = true)
public class ProtocolDriver extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String driverId;
    private String driverName;
    private String protocolType;
    private String driverVersion;
    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configSchema;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dataMapping;

    private String jarPath;
    private String className;
    private String description;

    public interface ProtocolType {
        String MODBUS = "modbus";
        String OPC_UA = "opc_ua";
        String MQTT = "mqtt";
        String HTTP = "http";
        String BACNET = "bacnet";
        String PROFIBUS = "profibus";
        String ETHERNET_IP = "ethernet_ip";
        String CANOPEN = "canopen";
        String S7 = "s7";
        String CUSTOM = "custom";
    }

    public interface Status {
        String LOADED = "loaded";
        String ACTIVE = "active";
        String INACTIVE = "inactive";
        String ERROR = "error";
    }
}
