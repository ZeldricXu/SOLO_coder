package com.metricplatform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class NotificationSendDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String templateId;

    @NotBlank(message = "渠道不能为空")
    private String channel;

    @NotBlank(message = "接收人不能为空")
    private String receiver;

    private String subject;

    private String content;

    private Map<String, Object> variables;

    private boolean async = true;
}
