package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentDTO {
    @NotBlank(message = "合同ID不能为空")
    private String contractId;

    @NotNull(message = "支付金额不能为空")
    @Positive(message = "支付金额必须大于0")
    private Double paymentAmount;

    private String paymentMethod = "wechat";
    private String paymentPeriod;
}
