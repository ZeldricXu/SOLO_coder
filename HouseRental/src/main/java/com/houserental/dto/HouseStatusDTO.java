package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HouseStatusDTO {
    @NotBlank(message = "房源ID不能为空")
    private String houseId;

    @NotBlank(message = "状态不能为空")
    private String status;
}
