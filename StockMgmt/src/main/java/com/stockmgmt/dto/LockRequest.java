package com.stockmgmt.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

@Data
public class LockRequest {

    @NotBlank(message = "商品ID不能为空")
    private String productId;

    private String warehouseId;

    @NotNull(message = "锁定数量不能为空")
    @Min(value = 1, message = "锁定数量必须大于0")
    private Integer quantity;

    @NotBlank(message = "参考单号不能为空")
    private String referenceNo;

    private String operator;

    private LocalDateTime expireTime;

    private String urgencyLevel;

    private String remark;
}
