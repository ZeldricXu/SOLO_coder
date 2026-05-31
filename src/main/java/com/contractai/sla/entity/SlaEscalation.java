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
@TableName("sla_escalation")
public class SlaEscalation extends TenantBaseEntity {

    private Long slaRecordId;

    private Integer escalationLevel;

    private String escalationType;

    private LocalDateTime escalationTime;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> notifiedUsers;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> notificationChannels;

    private Boolean acknowledged;

    private Long acknowledgedBy;

    private LocalDateTime acknowledgedAt;
}
