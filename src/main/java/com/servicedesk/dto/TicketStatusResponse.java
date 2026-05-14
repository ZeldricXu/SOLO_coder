package com.servicedesk.dto;

import com.servicedesk.entity.Ticket;
import lombok.Data;

@Data
public class TicketStatusResponse {
    private Ticket ticket;

    public TicketStatusResponse(Ticket ticket) {
        this.ticket = ticket;
    }
}
