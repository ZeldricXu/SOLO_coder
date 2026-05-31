package com.iotplatform.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class NotificationSendDTO {

    private String templateCode;

    @NotBlank(message = "渠道类型不能为空")
    private String channelType;

    @NotBlank(message = "接收人不能为空")
    private String recipient;

    private List<String> recipients;

    private String subject;

    private String content;

    private Map<String, Object> variables;

    private Integer priority = 5;

    private String callbackUrl;
}
