package com.logmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Duration;
import java.util.Map;

@Data
public class NotificationDTO {
    @NotBlank(message = "title is required")
    private String title;

    @NotBlank(message = "content is required")
    private String content;

    @NotBlank(message = "priority is required")
    private String priority;

    @NotBlank(message = "recipient is required")
    private String recipient;

    @NotBlank(message = "channel is required")
    private String channel;

    private Map<String, Object> payload;

    private String suppressionKey;

    private Duration suppressionDuration;
}
