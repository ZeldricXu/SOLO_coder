package com.travelbooking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CreateBookingRequest {
    @NotBlank(message = "线路ID不能为空")
    private String routeId;

    @NotBlank(message = "游客姓名不能为空")
    private String touristName;

    private String touristPhone;
    private String touristIdType;
    private String touristIdNumber;

    @Positive(message = "预订数量必须大于0")
    private Integer bookingCount;
}
