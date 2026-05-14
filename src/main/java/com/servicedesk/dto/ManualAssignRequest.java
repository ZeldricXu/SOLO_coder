package com.servicedesk.dto;

import lombok.Data;

@Data
public class ManualAssignRequest {
    @NotBlank(message = "工单ID不能为空")
    private String ticketId;

    @NotBlank(message = "客服ID不能为空")
    private String agentId;
}
