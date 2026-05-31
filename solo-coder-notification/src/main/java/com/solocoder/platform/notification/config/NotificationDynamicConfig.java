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
public class NotificationDynamicConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private Map<String, ChannelConfig> channelConfigs;
    private Map<String, String> globalSettings;
    private long configVersion;
    private long loadedAt;

    public ChannelConfig getChannelConfig(String channelType) {
        if (channelConfigs != null && channelConfigs.containsKey(channelType)) {
            return channelConfigs.get(channelType);
        }
        return ChannelConfig.defaultConfig(channelType);
    }

    public boolean isChannelEnabled(String channelType) {
        return getChannelConfig(channelType).isEnabled();
    }
}
