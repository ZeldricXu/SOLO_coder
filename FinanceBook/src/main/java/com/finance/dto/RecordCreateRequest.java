package com.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@With
public class RecordCreateRequest {

    @NotBlank(message = "账户ID不能为空")
    private String account_id;

    @NotBlank(message = "收支类型不能为空")
    private String record_type;

    @NotNull(message = "金额不能为空")
    @Positive(message = "金额必须大于0")
    private BigDecimal record_amount;

    @NotBlank(message = "分类不能为空")
    private String record_category;

    private String record_desc;
}
