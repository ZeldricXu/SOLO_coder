package com.eventticket.dto;

import lombok.Data;

@Data
public class TicketVerifyResponse {
    private String verifyResult;
    private String ticketId;
    private String eventName;
    private String seatNumber;
    private String participantName;
}
