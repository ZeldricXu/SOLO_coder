package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_instance")
public class ApprovalInstance extends TenantEntity {

    private Long flowId;

    private String businessKey;

    private String businessData;

    private Long initiatorId;

    private String currentNodeId;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String result;
}
