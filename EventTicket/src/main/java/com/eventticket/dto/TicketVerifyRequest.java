package com.eventticket.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class TicketVerifyRequest {
    
    @NotBlank(message = "票务ID不能为空")
    private String ticketId;
    
    private String operator;
}
