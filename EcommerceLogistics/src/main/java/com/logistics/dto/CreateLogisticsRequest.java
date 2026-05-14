package com.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateLogisticsRequest {

    @NotBlank(message = "order_id不能为空")
    private String orderId;

    @NotBlank(message = "station_id不能为空")
    private String stationId;

    private String deliveryTypeCode;

    private String urgencyLevel;
}
