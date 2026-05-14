package com.travelbooking.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RouteSearchItem {
    private String routeName;
    private Integer available;
}
