package com.contractai.sla.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sla_record")
public class SlaRecord extends TenantBaseEntity {

    private Long policyId;

    private String businessType;

    private String businessId;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime responseDeadline;

    private LocalDateTime resolutionDeadline;

    private LocalDateTime responseTime;

    private LocalDateTime resolutionTime;

    private String currentStage;

    private Integer escalationLevel;

    private LocalDateTime lastEscalationAt;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> notificationsSent;

    @TableField(exist = false)
    private Long remainingResponseMinutes;

    @TableField(exist = false)
    private Long remainingResolutionMinutes;

    @TableField(exist = false)
    private BigDecimal responseProgress;

    @TableField(exist = false)
    private BigDecimal resolutionProgress;

    @TableField(exist = false)
    private Boolean isWarning;

    @TableField(exist = false)
    private Boolean isBreached;
}
