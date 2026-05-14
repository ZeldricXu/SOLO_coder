package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FollowRequest {
    @NotBlank(message = "客户ID不能为空")
    private String customerId;
    private String salesId;
    private String followType;
    @NotBlank(message = "跟进内容不能为空")
    private String followContent;
    private String followResult;
    private LocalDateTime followTime;
    private LocalDateTime nextFollow;
}
