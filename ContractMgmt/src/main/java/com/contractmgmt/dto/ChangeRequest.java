package com.contractmgmt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class ChangeRequest {

    @NotBlank(message = "合同ID不能为空")
    private String contractId;

    @NotBlank(message = "变更类型不能为空")
    private String changeType;

    private BigDecimal changeBefore;
    private BigDecimal changeAfter;
    private String changeBeforeText;
    private String changeAfterText;

    @NotBlank(message = "变更原因不能为空")
    private String changeReason;

    private String operator;

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public BigDecimal getChangeBefore() {
        return changeBefore;
    }

    public void setChangeBefore(BigDecimal changeBefore) {
        this.changeBefore = changeBefore;
    }

    public BigDecimal getChangeAfter() {
        return changeAfter;
    }

    public void setChangeAfter(BigDecimal changeAfter) {
        this.changeAfter = changeAfter;
    }

    public String getChangeBeforeText() {
        return changeBeforeText;
    }

    public void setChangeBeforeText(String changeBeforeText) {
        this.changeBeforeText = changeBeforeText;
    }

    public String getChangeAfterText() {
        return changeAfterText;
    }

    public void setChangeAfterText(String changeAfterText) {
        this.changeAfterText = changeAfterText;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
