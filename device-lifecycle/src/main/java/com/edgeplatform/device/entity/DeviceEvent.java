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
@TableName(value = "device_event", autoResultMap = true)
public class DeviceEvent extends BaseEntity {

    private String eventId;

    private String deviceId;

    private String eventType;

    private String eventSource;

    private LocalDateTime eventTime;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> eventData;

    private String severity;
}
