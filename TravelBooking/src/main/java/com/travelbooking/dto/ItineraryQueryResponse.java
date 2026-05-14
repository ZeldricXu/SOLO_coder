package com.travelbooking.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;

@Data
@Builder
public class ItineraryQueryResponse {
    private String status;
    private LocalDate start;
    private LocalDate end;
    private String guideName;
}
