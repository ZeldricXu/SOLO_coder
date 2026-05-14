package com.servicedesk.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class TransferTicketRequest {
    @NotBlank(message = "工单ID不能为空")
    private String ticketId;

    @NotBlank(message = "目标客服ID不能为空")
    private String toAgentId;

    @NotBlank(message = "转派原因不能为空")
    private String transferReason;

    private String fromAgentId;
}
