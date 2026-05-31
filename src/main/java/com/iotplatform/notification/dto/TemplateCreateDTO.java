package com.iotplatform.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class TemplateCreateDTO {

    @NotBlank(message = "模板编码不能为空")
    private String templateCode;

    private String templateName;

    @NotBlank(message = "渠道类型不能为空")
    private String channelType;

    private String subjectTemplate;

    @NotBlank(message = "内容模板不能为空")
    private String contentTemplate;

    private Map<String, Object> variablesSchema;

    private Boolean enabled = true;
}
