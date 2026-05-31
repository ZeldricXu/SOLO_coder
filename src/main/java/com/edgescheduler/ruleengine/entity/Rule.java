package com.edgescheduler.ruleengine.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "rule", autoResultMap = true)
public class Rule extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String ruleId;

    private String ruleName;

    private String description;

    private String triggerType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> triggerConfig;

    private String conditionExpression;

    private String actionType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> actionConfig;

    private Integer enabled;

    private Integer version;

    public interface TriggerType {
        String TIMER = "timer";
        String EVENT = "event";
        String DATA = "data";
    }

    public interface ActionType {
        String COMMAND = "command";
        String NOTIFY = "notify";
        String MQTT = "mqtt";
        String HTTP = "http";
    }
}
