package com.servicedesk.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriorityTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String ticketTitle;
    private String ticketContent;
    private String ticketCategory;
    private String customerId;
    private String ticketPriority;
    private Instant createdAt;
    private int retryCount;
    private String status;

    public static PriorityTask fromRequest(com.servicedesk.dto.CreateTicketRequest request) {
        return PriorityTask.builder()
                .taskId(UUID.randomUUID().toString())
                .ticketTitle(request.getTicketTitle())
                .ticketContent(request.getTicketContent())
                .ticketCategory(request.getTicketCategory())
                .customerId(request.getCustomerId())
                .ticketPriority(request.getTicketPriority())
                .createdAt(Instant.now())
                .retryCount(0)
                .status("pending")
                .build();
    }

    public com.servicedesk.dto.CreateTicketRequest toRequest() {
        com.servicedesk.dto.CreateTicketRequest request = new com.servicedesk.dto.CreateTicketRequest();
        request.setTicketTitle(this.ticketTitle);
        request.setTicketContent(this.ticketContent);
        request.setTicketCategory(this.ticketCategory);
        request.setCustomerId(this.customerId);
        request.setTicketPriority(this.ticketPriority);
        return request;
    }
}
