package com.servicedesk.dto;

import lombok.Data;

@Data
public class TicketResponseResponse {
    private String responseId;

    public TicketResponseResponse(String responseId) {
        this.responseId = responseId;
    }
}
