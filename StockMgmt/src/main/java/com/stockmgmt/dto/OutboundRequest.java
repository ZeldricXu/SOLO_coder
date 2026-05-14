package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class OutboundRequest {

    @NotBlank(message = "商品ID不能为空")
    private String productId;

    @NotNull(message = "出库数量不能为空")
    @Min(value = 1, message = "出库数量必须大于0")
    private Integer quantity;

    private String warehouseId;

    private String operator;

    @NotBlank(message = "参考单号不能为空")
    private String referenceNo;

    private String remark;

    private Boolean needLock = true;

    private String urgencyLevel;

    private Boolean async = false;
}
