package com.travelbooking.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ItineraryQueryWrapper {
    private ItineraryQueryResponse itinerary;
}
