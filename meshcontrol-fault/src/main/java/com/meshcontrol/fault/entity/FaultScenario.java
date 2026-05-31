package com.meshcontrol.fault.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "fault_scenario", autoResultMap = true)
public class FaultScenario extends BaseEntity {

    private String scenarioId;
    private String name;
    private String description;
    private String faultType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> targetSelector;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> injectionConfig;

    private Integer durationSeconds;
    private Boolean autoRollback;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> rollbackConfig;

    private Boolean enabled;
    private String status;
}
