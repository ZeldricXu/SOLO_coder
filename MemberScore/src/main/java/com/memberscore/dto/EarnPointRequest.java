package com.memberscore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class EarnPointRequest {
    
    @NotBlank(message = "会员ID不能为空")
    private String memberId;
    
    @NotBlank(message = "积分来源不能为空")
    private String pointSource;
    
    @NotNull(message = "积分数值不能为空")
    @Positive(message = "积分数值必须为正数")
    private Integer pointAmount;
}
