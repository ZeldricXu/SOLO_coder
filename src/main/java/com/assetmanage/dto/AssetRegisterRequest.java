package com.assetmanage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AssetRegisterRequest {

    @NotBlank(message = "资产名称不能为空")
    private String assetName;

    @NotBlank(message = "资产类型不能为空")
    private String assetType;

    private String assetCategory;
    private String assetModel;
    private String assetSn;
    private LocalDate purchaseDate;

    @NotNull(message = "购买价格不能为空")
    private BigDecimal purchasePrice;

    private String depreciationMethod;
    private BigDecimal depreciationRate;
    private Integer usefulLife;
    private String location;
    private String department;
}
