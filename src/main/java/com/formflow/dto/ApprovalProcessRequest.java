package com.formflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalProcessRequest {

    @NotBlank(message = "任务ID不能为空")
    private String taskId;

    @NotBlank(message = "审批结果不能为空")
    private String approvalResult;

    private String approvalComment;

    private String approverId;

    private String approverName;
}
