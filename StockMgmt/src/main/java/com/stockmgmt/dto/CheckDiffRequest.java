package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class CheckDiffRequest {

    @NotNull(message = "盘点ID不能为空")
    private String checkId;

    @NotNull(message = "库存ID不能为空")
    private String stockId;

    @NotNull(message = "实际数量不能为空")
    private Integer actualQuantity;

    private String diffReason;

    private String operator;
}
