package com.edgescheduler.device.entity;

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
@TableName(value = "device", autoResultMap = true)
public class Device extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String deviceKey;

    private String deviceName;

    private String deviceType;

    private String productKey;

    private String firmwareVersion;

    private String status;

    private String authType;

    private String authSecret;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private LocalDateTime lastOnlineAt;

    private LocalDateTime activatedAt;

    public interface Status {
        String INACTIVE = "inactive";
        String ACTIVE = "active";
        String OFFLINE = "offline";
        String ONLINE = "online";
        String DEACTIVATED = "deactivated";
    }

    public interface AuthType {
        String TOKEN = "token";
        String CERTIFICATE = "certificate";
        String SECRET = "secret";
    }
}
