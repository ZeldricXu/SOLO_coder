package com.mobilestore.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class StatisticsRequest {

    private String appId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String type;
}
