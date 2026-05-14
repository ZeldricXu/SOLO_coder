package com.assetmanage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AssetUseRequest {

    @NotBlank(message = "资产ID不能为空")
    private String assetId;

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotBlank(message = "使用类型不能为空")
    private String usageType;

    private LocalDate expectedReturn;
    private String operatorId;
}
