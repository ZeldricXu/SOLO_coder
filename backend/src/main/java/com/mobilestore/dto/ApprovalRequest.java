package com.mobilestore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalRequest {

    @NotBlank(message = "版本ID不能为空")
    private String versionId;

    @NotBlank(message = "审批结果不能为空")
    private String result;

    private String comment;
    private String approver;
}
