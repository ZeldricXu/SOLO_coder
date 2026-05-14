package com.servicedesk.dto;

import lombok.Data;

@Data
public class CreateTicketResponse {
    private String ticketId;
    private String status;

    public CreateTicketResponse(String ticketId, String status) {
        this.ticketId = ticketId;
        this.status = status;
    }
}
