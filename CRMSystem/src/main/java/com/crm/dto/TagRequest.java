package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TagRequest {
    @NotBlank(message = "标签名称不能为空")
    private String tagName;
    private String tagType;
    private String tagStatus;
}
