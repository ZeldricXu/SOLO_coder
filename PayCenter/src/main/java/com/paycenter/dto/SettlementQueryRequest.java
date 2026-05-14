package com.paycenter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SettlementQueryRequest {
    @NotBlank(message = "商户ID不能为空")
    private String merchantId;
    private LocalDate startDate;
    private LocalDate endDate;
}
