package com.solocoder.platform.notification.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "Channel must not be blank")
    private String channel;

    @NotBlank(message = "Recipient must not be blank")
    private String recipient;

    private String templateId;
    private Map<String, Object> templateParams;
    private String plainContent;
    private Map<String, String> headers;
    private int priority;
}
