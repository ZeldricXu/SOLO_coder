package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import com.designsystem.common.enums.ApprovalStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_approval_request")
public class ApprovalRequest extends BaseEntity {
    private String requestType;
    private Long targetId;
    private String targetType;
    private String title;
    private String description;
    private String changeContent;
    private Long approverId;
    private ApprovalStatus status;
    private String approvalComment;
    private LocalDateTime approvedAt;
    private String rejectReason;
    private Long submittedBy;
    private LocalDateTime submittedAt;
}
