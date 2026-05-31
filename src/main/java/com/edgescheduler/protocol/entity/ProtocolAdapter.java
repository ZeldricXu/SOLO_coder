package com.edgescheduler.protocol.entity;

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
@TableName(value = "protocol_adapter", autoResultMap = true)
public class ProtocolAdapter extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String adapterId;
    private String adapterName;
    private String driverId;
    private String deviceKey;
    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> connectionParams;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> adapterConfig;

    private LocalDateTime lastConnectedAt;
    private LocalDateTime lastDisconnectedAt;
    private Long totalMessages;
    private Long errorMessages;
    private String lastError;

    public interface Status {
        String CREATED = "created";
        String CONNECTING = "connecting";
        String CONNECTED = "connected";
        String DISCONNECTED = "disconnected";
        String ERROR = "error";
        String STOPPED = "stopped";
    }
}
