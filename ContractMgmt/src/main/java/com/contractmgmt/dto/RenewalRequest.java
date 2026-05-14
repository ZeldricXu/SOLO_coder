package com.contractmgmt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public class RenewalRequest {

    @NotBlank(message = "原合同ID不能为空")
    private String originalContractId;

    @NotNull(message = "续签金额不能为空")
    private BigDecimal renewalAmount;

    @NotNull(message = "续签开始日期不能为空")
    private LocalDate renewalStart;

    @NotNull(message = "续签结束日期不能为空")
    private LocalDate renewalEnd;

    private String renewalReason;
    private String operator;

    public String getOriginalContractId() {
        return originalContractId;
    }

    public void setOriginalContractId(String originalContractId) {
        this.originalContractId = originalContractId;
    }

    public BigDecimal getRenewalAmount() {
        return renewalAmount;
    }

    public void setRenewalAmount(BigDecimal renewalAmount) {
        this.renewalAmount = renewalAmount;
    }

    public LocalDate getRenewalStart() {
        return renewalStart;
    }

    public void setRenewalStart(LocalDate renewalStart) {
        this.renewalStart = renewalStart;
    }

    public LocalDate getRenewalEnd() {
        return renewalEnd;
    }

    public void setRenewalEnd(LocalDate renewalEnd) {
        this.renewalEnd = renewalEnd;
    }

    public String getRenewalReason() {
        return renewalReason;
    }

    public void setRenewalReason(String renewalReason) {
        this.renewalReason = renewalReason;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
