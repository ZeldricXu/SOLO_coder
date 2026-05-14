package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TemplateCreateRequest {

    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    @NotBlank(message = "模板类型不能为空")
    private String templateType;

    private String templateDescription;

    @NotBlank(message = "模板题目不能为空")
    private String templateQuestions;
}
