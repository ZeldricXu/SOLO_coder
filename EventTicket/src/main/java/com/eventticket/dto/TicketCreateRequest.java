package com.eventticket.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class TicketCreateRequest {
    
    @NotBlank(message = "活动ID不能为空")
    private String eventId;
    
    private String seatId;
    
    @NotBlank(message = "参与者姓名不能为空")
    private String participantName;
    
    @NotBlank(message = "参与者手机号不能为空")
    private String participantPhone;
    
    private String participantIdType;
    
    private String participantIdNumber;
    
    private String paymentMethod;

    private String ticketType;

    private String section;

    private Integer ticketPrice;
}
