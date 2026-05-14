package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

@Data
public class InboundRequest {

    @NotBlank(message = "商品ID不能为空")
    private String productId;

    private String productName;

    private String skuId;

    @NotNull(message = "入库数量不能为空")
    @Min(value = 1, message = "入库数量必须大于0")
    private Integer quantity;

    @NotBlank(message = "批次号不能为空")
    private String batchNo;

    @NotBlank(message = "位置ID不能为空")
    private String locationId;

    private String warehouseId;

    private String unit;

    private java.math.BigDecimal costPrice;

    private LocalDate productionDate;

    private LocalDate expireDate;

    private String supplier;

    private String operator;

    private String referenceNo;

    private String remark;

    private Integer warningThreshold;

    private Integer overstockThreshold;

    private Boolean async = false;
}
