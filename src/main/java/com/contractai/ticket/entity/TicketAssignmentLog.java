package com.contractai.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_assignment_log")
public class TicketAssignmentLog extends TenantBaseEntity {

    private Long ticketId;

    private String assignmentType;

    private Long fromAssigneeId;

    private Long toAssigneeId;

    private String assignmentReason;

    private String assignmentStrategy;

    private BigDecimal matchScore;

    private BigDecimal loadBalanceFactor;

    private Long assignedBy;

    private LocalDateTime assignedAt;
}
