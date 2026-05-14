package com.servicedesk.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class TicketResponseRequest {
    @NotBlank(message = "工单ID不能为空")
    private String ticketId;

    @NotBlank(message = "响应内容不能为空")
    private String responseContent;

    private String agentId;
    private String responseType;
    private Boolean markResolved;
}
