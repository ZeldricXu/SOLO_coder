package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationApproveDTO {
    @NotBlank(message = "申请ID不能为空")
    private String applicationId;

    @NotBlank(message = "审批人ID不能为空")
    private String approverId;

    private LocalDate contractStart;
    private LocalDate contractEnd;
    private Double contractRent;
}
