package com.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetSetRequest {

    @NotBlank(message = "账户ID不能为空")
    private String account_id;

    @NotBlank(message = "预算分类不能为空")
    private String budget_category;

    @NotNull(message = "预算金额不能为空")
    @Positive(message = "预算金额必须大于0")
    private BigDecimal budget_amount;

    private String budget_period;
}
