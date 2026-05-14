package com.eventticket.dto;

import lombok.Data;

@Data
public class TicketCreateResponse {
    private String ticketId;
    private String ticketNumber;
    private String status;
    private Integer ticketPrice;
}
