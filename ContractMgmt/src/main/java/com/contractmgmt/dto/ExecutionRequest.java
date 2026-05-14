package com.contractmgmt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class ExecutionRequest {

    @NotBlank(message = "合同ID不能为空")
    private String contractId;

    @NotBlank(message = "执行类型不能为空")
    private String executionType;

    private BigDecimal executionAmount;

    @NotNull(message = "执行进度不能为空")
    private Integer executionProgress;

    private String executionDescription;
    private String operator;

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getExecutionType() {
        return executionType;
    }

    public void setExecutionType(String executionType) {
        this.executionType = executionType;
    }

    public BigDecimal getExecutionAmount() {
        return executionAmount;
    }

    public void setExecutionAmount(BigDecimal executionAmount) {
        this.executionAmount = executionAmount;
    }

    public Integer getExecutionProgress() {
        return executionProgress;
    }

    public void setExecutionProgress(Integer executionProgress) {
        this.executionProgress = executionProgress;
    }

    public String getExecutionDescription() {
        return executionDescription;
    }

    public void setExecutionDescription(String executionDescription) {
        this.executionDescription = executionDescription;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
