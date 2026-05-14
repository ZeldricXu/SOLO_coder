package com.formflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalProcessResponse {
    private String approvalId;
    private String instanceId;
    private String instanceStatus;
    private String currentNodeName;
    private Boolean isProcessCompleted;
}
