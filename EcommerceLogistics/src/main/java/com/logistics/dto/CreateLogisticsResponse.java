package com.logistics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLogisticsResponse {
    private String logisticsId;
    private String logisticsNumber;
    private String deliveryTypeCode;
    private String urgencyLevel;
}
