package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotificationManager {

    private static final Logger logger = LoggerFactory.getLogger(NotificationManager.class);

    private final Map<String, NotificationChannel> channels;

    public NotificationManager() {
        this.channels = new HashMap<>();
    }

    public void addChannel(NotificationChannel channel) {
        if (channel != null) {
            this.channels.put(channel.getName(), channel);
        }
    }

    public void addChannel(NotificationConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }

        NotificationChannel channel = createChannel(config);
        if (channel != null) {
            addChannel(channel);
        }
    }

    public void addChannels(List<NotificationConfig> configs) {
        if (configs != null) {
            for (NotificationConfig config : configs) {
                addChannel(config);
            }
        }
    }

    private NotificationChannel createChannel(NotificationConfig config) {
        switch (config.getType()) {
            case EMAIL:
                return new EmailNotifier(config);
            case WECHAT_WORK:
                return new WeChatWorkNotifier(config);
            case SLACK:
                return new SlackNotifier(config);
            default:
                logger.warn("Unsupported channel type: {}", config.getType());
                return null;
        }
    }

    public boolean sendNotification(AlertEvent alert, List<String> channelNames) {
        if (alert == null) {
            return false;
        }

        boolean success = false;
        List<String> channelsToUse = channelNames != null && !channelNames.isEmpty() ?
            channelNames : new ArrayList<>(channels.keySet());

        for (String channelName : channelsToUse) {
            NotificationChannel channel = channels.get(channelName);
            if (channel == null || !channel.isEnabled()) {
                logger.warn("Channel '{}' not found or disabled", channelName);
                continue;
            }

            try {
                boolean result = channel.send(alert);
                if (result) {
                    success = true;
                    logger.info("Notification sent via channel '{}'", channelName);
                } else {
                    logger.warn("Failed to send notification via channel '{}'", channelName);
                }
            } catch (Exception e) {
                logger.error("Exception sending notification via channel '{}'", channelName, e);
            }
        }

        return success;
    }

    public boolean sendNotification(AlertEvent alert) {
        return sendNotification(alert, null);
    }

    public List<NotificationChannel> getChannels() {
        return new ArrayList<>(channels.values());
    }

    public NotificationChannel getChannel(String name) {
        return channels.get(name);
    }

    public boolean hasChannel(String name) {
        return channels.containsKey(name);
    }

    public void removeChannel(String name) {
        channels.remove(name);
    }

    public void clear() {
        channels.clear();
    }
}
