package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("config_change_log")
public class ConfigChangeLog extends BaseEntity {

    private String logId;
    private String configId;
    private String configKey;
    private Map<String, Object> oldValue;
    private Map<String, Object> newValue;
    private String changeType;
    private String changedBy;
    private LocalDateTime changedAt;
    private String changeReason;
    private String status;
    private String rollbackStatus;
}
