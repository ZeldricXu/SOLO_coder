package com.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HireApproveRequest {
    @NotBlank(message = "简历ID不能为空")
    private String resumeId;

    @NotBlank(message = "录用薪资不能为空")
    private String hireSalary;

    private LocalDate hireDate;
    private Boolean approved;
}
