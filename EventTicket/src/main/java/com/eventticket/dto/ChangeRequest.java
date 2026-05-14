package com.eventticket.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class ChangeRequest {
    
    @NotBlank(message = "票务ID不能为空")
    private String ticketId;
    
    @NotBlank(message = "退改类型不能为空")
    private String changeType;
    
    private String changeReason;
    
    private String newSeatId;
}
