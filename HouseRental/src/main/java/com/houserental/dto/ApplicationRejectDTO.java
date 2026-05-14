package com.houserental.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApplicationRejectDTO {
    @NotBlank(message = "申请ID不能为空")
    private String applicationId;

    @NotBlank(message = "审批人ID不能为空")
    private String approverId;

    private String rejectReason;
}
