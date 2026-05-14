package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class TransferRequest {

    @NotBlank(message = "商品ID不能为空")
    private String productId;

    @NotNull(message = "调拨数量不能为空")
    @Min(value = 1, message = "调拨数量必须大于0")
    private Integer quantity;

    @NotBlank(message = "源仓库ID不能为空")
    private String fromWarehouseId;

    @NotBlank(message = "目标仓库ID不能为空")
    private String toWarehouseId;

    private String fromLocationId;

    private String toLocationId;

    private String operator;

    private String referenceNo;

    private String remark;
}
