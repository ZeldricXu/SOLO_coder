package com.contractmgmt.dto;

import jakarta.validation.constraints.NotBlank;

public class ApprovalRequest {

    @NotBlank(message = "合同ID不能为空")
    private String contractId;

    @NotBlank(message = "审批状态不能为空")
    private String approvalStatus;

    private String approvalComment;

    @NotBlank(message = "审批人不能为空")
    private String approver;

    private String approvalType = "create";

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(String approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public String getApprovalComment() {
        return approvalComment;
    }

    public void setApprovalComment(String approvalComment) {
        this.approvalComment = approvalComment;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public String getApprovalType() {
        return approvalType;
    }

    public void setApprovalType(String approvalType) {
        this.approvalType = approvalType;
    }
}
