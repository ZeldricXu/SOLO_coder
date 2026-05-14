package com.memberscore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ConsumePointRequest {
    
    @NotBlank(message = "会员ID不能为空")
    private String memberId;
    
    @NotNull(message = "消费积分数值不能为空")
    @Positive(message = "消费积分数值必须为正数")
    private Integer consumeAmount;
    
    @NotBlank(message = "消费类型不能为空")
    private String consumeType;
}
