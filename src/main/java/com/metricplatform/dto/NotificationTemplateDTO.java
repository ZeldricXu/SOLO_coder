package com.metricplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class NotificationTemplateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    @NotBlank(message = "渠道不能为空")
    @Pattern(regexp = "^(email|sms|webhook|dingtalk|wechat)$", message = "无效的通知渠道")
    private String channel;

    private String subjectTemplate;

    @NotBlank(message = "内容模板不能为空")
    private String contentTemplate;

    private Map<String, Object> variables;

    private Boolean enabled = true;
}
