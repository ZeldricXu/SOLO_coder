package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OpportunityRequest {
    @NotBlank(message = "客户ID不能为空")
    private String customerId;
    private String salesId;
    @NotNull(message = "机会金额不能为空")
    private Double opportunityAmount;
    private String opportunityStage;
    private Integer opportunityProb;
}
