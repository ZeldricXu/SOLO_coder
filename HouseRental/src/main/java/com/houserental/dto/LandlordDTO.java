package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LandlordDTO {
    @NotBlank(message = "房东姓名不能为空")
    private String landlordName;

    @NotBlank(message = "房东联系方式不能为空")
    private String landlordPhone;

    private String landlordStatus = "active";
}
