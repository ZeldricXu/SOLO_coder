package com.solocoder.platform.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    private String templateId;
    private String channel;
    private String subject;
    private String content;
    private Map<String, String> defaultParams;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int version;
}
