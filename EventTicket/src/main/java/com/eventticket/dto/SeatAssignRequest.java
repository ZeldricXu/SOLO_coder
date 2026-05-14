package com.eventticket.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class SeatAssignRequest {
    
    @NotBlank(message = "活动ID不能为空")
    private String eventId;
    
    private String seatId;
    
    private String section;

    private String ticketType;
}
