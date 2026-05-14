package com.memberscore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MemberCreateRequest {
    
    @NotBlank(message = "会员ID不能为空")
    private String memberId;
    
    @NotBlank(message = "用户ID不能为空")
    private String userId;
}
