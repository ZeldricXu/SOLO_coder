package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerTagRequest {
    @NotBlank(message = "客户ID不能为空")
    private String customerId;
    @NotBlank(message = "标签ID不能为空")
    private String tagId;
}
