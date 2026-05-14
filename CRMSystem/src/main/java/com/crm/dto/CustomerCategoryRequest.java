package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerCategoryRequest {
    @NotBlank(message = "客户ID不能为空")
    private String customerId;
    @NotBlank(message = "分类ID不能为空")
    private String categoryId;
}
