package com.meeting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "meeting.type")
public class MeetingTypeConfig {

    private String defaultType = "regular";
    private boolean enableDynamicConfig = true;
    private long cacheTtlSeconds = 300;
}
