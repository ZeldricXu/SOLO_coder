package com.servicedesk.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class CreateTicketRequest {
    @NotBlank(message = "工单标题不能为空")
    @Size(max = 200, message = "工单标题不能超过200个字符")
    private String ticketTitle;

    @NotBlank(message = "工单内容不能为空")
    private String ticketContent;

    @NotBlank(message = "工单分类不能为空")
    private String ticketCategory;

    private String customerId;
    private String ticketPriority;
}
