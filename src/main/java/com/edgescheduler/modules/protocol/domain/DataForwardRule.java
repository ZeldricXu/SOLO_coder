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
@TableName("data_forward_rule")
public class DataForwardRule extends BaseEntity {

    @TableField("rule_id")
    private String ruleId;

    @TableField("rule_name")
    private String ruleName;

    @TableField("source_protocol")
    private String sourceProtocol;

    @TableField("target_protocol")
    private String targetProtocol;

    @TableField("source_topic")
    private String sourceTopic;

    @TableField("target_topic")
    private String targetTopic;

    @TableField(value = "data_mapping", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> dataMapping;

    @TableField("forward_mode")
    private String forwardMode;

    @TableField("qos")
    private Integer qos;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("last_forward_time")
    private LocalDateTime lastForwardTime;

    @TableField("forward_count")
    private Long forwardCount;
}
