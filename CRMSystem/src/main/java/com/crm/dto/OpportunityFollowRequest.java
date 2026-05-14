package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OpportunityFollowRequest {
    @NotBlank(message = "机会ID不能为空")
    private String opportunityId;
    private String salesId;
    private String opportunityStage;
    private Integer opportunityProb;
    private String action;
    private String failReason;
}
