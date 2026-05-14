package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TypeCreateRequest {

    @NotBlank(message = "类型编码不能为空")
    private String typeCode;

    @NotBlank(message = "类型名称不能为空")
    private String typeName;

    private String typeDescription;

    private String typeCategory;

    private String typeIcon;

    private String typeColor;

    private String typeConfig;

    private Integer sortOrder;
}
