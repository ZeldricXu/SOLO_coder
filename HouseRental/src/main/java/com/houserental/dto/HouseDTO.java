package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HouseDTO {
    @NotBlank(message = "房东ID不能为空")
    private String landlordId;

    @NotBlank(message = "房源地址不能为空")
    private String houseAddress;

    private String houseType = "apartment";

    @NotNull(message = "房屋面积不能为空")
    @Positive(message = "房屋面积必须大于0")
    private Double houseArea;

    @NotNull(message = "租金不能为空")
    @Positive(message = "租金必须大于0")
    private Double houseRent;

    private List<String> houseFeatures;
}
