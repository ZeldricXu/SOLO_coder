package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CheckCreateRequest {

    @NotBlank(message = "仓库ID不能为空")
    private String warehouseId;

    @NotBlank(message = "盘点类型不能为空")
    private String checkType;

    private String checkName;

    private String operator;

    private String remark;
}
