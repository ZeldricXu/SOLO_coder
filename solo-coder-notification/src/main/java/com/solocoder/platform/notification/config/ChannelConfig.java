package com.solocoder.platform.notification.config;

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
public class ChannelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String channelType;
    private boolean enabled;
    private int rateLimitPerSecond;
    private int timeoutMs;
    private int maxRetries;
    private long retryIntervalMs;
    private Map<String, String> extra;

    public static ChannelConfig defaultConfig(String channelType) {
        return ChannelConfig.builder()
                .channelType(channelType)
                .enabled(true)
                .rateLimitPerSecond(100)
                .timeoutMs(5000)
                .maxRetries(3)
                .retryIntervalMs(1000)
                .build();
    }
}
