package com.meshcontrol.fault.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "fault_injection", autoResultMap = true)
public class FaultInjection extends BaseEntity {

    private String injectionId;
    private String scenarioId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> targets;

    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime rollbackStartedAt;
    private LocalDateTime rollbackCompletedAt;
    private String errorDetail;
}
