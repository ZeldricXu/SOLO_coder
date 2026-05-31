package com.contractai.approval.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_stage")
public class ApprovalStage extends TenantBaseEntity {

    private Long processId;

    private Integer stageIndex;

    private String stageName;

    private String approvalStrategy;

    private String status;

    private Integer approverCount;

    private Integer approvedCount;

    private Integer rejectedCount;

    private String signType;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
