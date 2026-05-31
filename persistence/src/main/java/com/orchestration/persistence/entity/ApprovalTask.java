package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_task")
public class ApprovalTask extends TenantEntity {

    private Long instanceId;

    private String nodeId;

    private String nodeName;

    private String approvalType;

    private Long assigneeId;

    private String candidateUsers;

    private String candidateGroups;

    private String status;

    private String comment;

    private LocalDateTime approvedAt;
}
