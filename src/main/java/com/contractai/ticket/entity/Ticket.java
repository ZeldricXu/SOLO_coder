package com.contractai.ticket.entity;

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
@TableName("ticket")
public class Ticket extends TenantBaseEntity {

    private String ticketNo;

    private String title;

    private String description;

    private String ticketType;

    private Integer priority;

    private String status;

    private String source;

    private String category;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> tags;

    private Long assigneeId;

    private String assigneeGroup;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Long> requiredSkills;

    private Long slaPolicyId;

    private Long parentId;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> formData;

    private Long createdBy;

    private LocalDateTime resolvedAt;

    private LocalDateTime closedAt;

    @TableField(exist = false)
    private List<TicketAssignmentLog> assignmentLogs;

    @TableField(exist = false)
    private com.contractai.skill.entity.Employee assignee;

    @TableField(exist = false)
    private BigDecimal matchScore;

    @TableField(exist = false)
    private BigDecimal workloadScore;

    @TableField(exist = false)
    private BigDecimal finalScore;
}
