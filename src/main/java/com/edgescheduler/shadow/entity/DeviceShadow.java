package com.edgescheduler.shadow.entity;

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
@TableName(value = "device_shadow", autoResultMap = true)
public class DeviceShadow extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String deviceKey;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> desired;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> reported;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> delta;

    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;

    private LocalDateTime lastSyncAt;
    private LocalDateTime lastDesiredUpdateAt;
    private LocalDateTime lastReportedUpdateAt;
}
