package com.datastandard.modules.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateDefinition {

    private String code;

    private String name;

    private String description;

    private String type;

    private Set<String> supportedChannels;

    private String subjectTemplate;

    private String contentTemplate;

    private String htmlTemplate;

    private Map<String, String> defaultParams;

    private String version;

    private Instant createdAt;

    private Instant updatedAt;

    private boolean enabled;
}
