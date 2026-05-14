package com.travelbooking.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class RouteSearchResponse {
    private List<RouteSearchItem> routes;
}
