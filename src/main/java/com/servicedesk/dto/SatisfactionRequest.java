package com.servicedesk.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

@Data
public class SatisfactionRequest {
    @NotBlank(message = "工单ID不能为空")
    private String ticketId;

    @Min(value = 1, message = "满意度评分最小为1")
    @Max(value = 5, message = "满意度评分最大为5")
    private Integer satisfactionScore;

    private String satisfactionComment;
    private String customerId;
}
