package com.datastandard.modules.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    @NotBlank(message = "通知类型不能为空")
    private String type;

    @NotEmpty(message = "接收人不能为空")
    private List<String> recipients;

    @NotBlank(message = "模板编码不能为空")
    private String templateCode;

    private Map<String, Object> templateParams;

    private String subject;

    private String content;

    @Builder.Default
    private int priority = 5;

    private Instant scheduledTime;

    private String sender;

    private String traceId;

    private Map<String, Object> metadata;

    private int retryCount;

    @Builder.Default
    private boolean async = true;
}
