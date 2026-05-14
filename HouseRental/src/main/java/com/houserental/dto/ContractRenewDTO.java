package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractRenewDTO {
    @NotBlank(message = "合同ID不能为空")
    private String contractId;

    @NotNull(message = "新合同开始日期不能为空")
    private LocalDate newContractStart;

    @NotNull(message = "新合同结束日期不能为空")
    private LocalDate newContractEnd;

    private Double newRent;
}
