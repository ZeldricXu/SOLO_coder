package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ticket_assignment_log")
public class TicketAssignmentLog extends BaseEntity {

    private Long ticketId;
    private Long fromAssigneeId;
    private String fromAssigneeName;
    private Long toAssigneeId;
    private String toAssigneeName;
    private Integer matchScore;
    private Integer loadBefore;
    private Integer loadAfter;
    private String reason;
    private LocalDateTime assignedAt;
}
