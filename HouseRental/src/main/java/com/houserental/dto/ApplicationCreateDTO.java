package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationCreateDTO {
    @NotBlank(message = "房源ID不能为空")
    private String houseId;

    @NotBlank(message = "租客姓名不能为空")
    private String tenantName;

    @NotBlank(message = "租客联系方式不能为空")
    private String tenantPhone;

    private String tenantIdType = "identity";
    private String tenantIdNumber;
}
