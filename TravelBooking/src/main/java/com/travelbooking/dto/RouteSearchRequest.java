package com.travelbooking.dto;

import lombok.Data;

@Data
public class RouteSearchRequest {
    private String routeType;
    private String departureDate;
    private String routeStatus;
}
