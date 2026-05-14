package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WarningHandleRequest {

    @NotBlank(message = "处理人不能为空")
    private String handledBy;

    private String remark;
}
