package com.eventticket.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EventSearchResponse {
    private String eventId;
    private String eventName;
    private String eventType;
    private LocalDateTime eventDate;
    private String eventVenue;
    private Integer eventCapacity;
    private Integer availableSeats;
    private String eventStatus;
}
