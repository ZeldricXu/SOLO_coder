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

    private final Map<String, Notifier> notifiers;
    private final TemplateEngine templateEngine;

    public NotificationManager() {
        this.notifiers = new HashMap<>();
        this.templateEngine = new TemplateEngine();
    }

    public void addNotifier(Notifier notifier) {
        if (notifier != null) {
            this.notifiers.put(notifier.getName(), notifier);
        }
    }

    @Deprecated
    public void addChannel(NotificationChannel channel) {
        addNotifier(channel);
    }

    public void addChannel(NotificationConfig config) {
        if (config == null || !config.isEnabled()) {
            return;
        }

        Notifier notifier = createNotifier(config);
        if (notifier != null) {
            addNotifier(notifier);
        }
    }

    public void addChannels(List<NotificationConfig> configs) {
        if (configs != null) {
            for (NotificationConfig config : configs) {
                addChannel(config);
            }
        }
    }

    private Notifier createNotifier(NotificationConfig config) {
        switch (config.getType()) {
            case EMAIL:
                return new EmailNotifier(config, templateEngine);
            case WECHAT_WORK:
                return new WeChatWorkNotifier(config, templateEngine);
            case SLACK:
                return new SlackNotifier(config, templateEngine);
            default:
                logger.warn("Unsupported channel type: {}", config.getType());
                return null;
        }
    }

    @Deprecated
    private NotificationChannel createChannel(NotificationConfig config) {
        return (NotificationChannel) createNotifier(config);
    }

    public boolean sendNotification(AlertEvent alert, List<String> channelNames) {
        if (alert == null) {
            return false;
        }

        boolean success = false;
        List<String> channelsToUse = channelNames != null && !channelNames.isEmpty() ?
            channelNames : new ArrayList<>(notifiers.keySet());

        for (String channelName : channelsToUse) {
            Notifier notifier = notifiers.get(channelName);
            if (notifier == null || !notifier.isEnabled()) {
                logger.warn("Channel '{}' not found or disabled", channelName);
                continue;
            }

            try {
                boolean result = notifier.send(alert);
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

    @Deprecated
    public List<NotificationChannel> getChannels() {
        List<NotificationChannel> channels = new ArrayList<>();
        for (Notifier notifier : notifiers.values()) {
            if (notifier instanceof NotificationChannel) {
                channels.add((NotificationChannel) notifier);
            }
        }
        return channels;
    }

    public List<Notifier> getNotifiers() {
        return new ArrayList<>(notifiers.values());
    }

    @Deprecated
    public NotificationChannel getChannel(String name) {
        Notifier notifier = notifiers.get(name);
        if (notifier instanceof NotificationChannel) {
            return (NotificationChannel) notifier;
        }
        return null;
    }

    public Notifier getNotifier(String name) {
        return notifiers.get(name);
    }

    public boolean hasChannel(String name) {
        return notifiers.containsKey(name);
    }

    public void removeChannel(String name) {
        notifiers.remove(name);
    }

    public void resetAll() {
        for (Notifier notifier : notifiers.values()) {
            notifier.reset();
        }
    }

    public void shutdown() {
        for (Notifier notifier : notifiers.values()) {
            if (notifier instanceof AbstractNotifier) {
                ((AbstractNotifier) notifier).shutdown();
            }
        }
        notifiers.clear();
    }

    public void clear() {
        shutdown();
    }

    public TemplateEngine getTemplateEngine() {
        return templateEngine;
    }
}
