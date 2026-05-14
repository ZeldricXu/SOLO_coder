package com.travelbooking.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateBookingResponse {
    private String bookingId;
    private String status;
}
