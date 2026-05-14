package com.formflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class FormSubmitRequest {

    @NotBlank(message = "模板ID不能为空")
    private String templateId;

    @NotNull(message = "表单数据不能为空")
    private Map<String, Object> formData;

    private String submitterId;

    private String submitterName;

    private String remark;
}
