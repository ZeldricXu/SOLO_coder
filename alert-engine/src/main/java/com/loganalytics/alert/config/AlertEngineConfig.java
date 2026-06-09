package com.loganalytics.alert.config;

import java.time.Duration;

public class AlertEngineConfig {
    private Duration evaluationInterval = Duration.ofSeconds(30);
    private Duration defaultCooldownPeriod = Duration.ofMinutes(5);
    private Duration defaultEscalationDelay = Duration.ofMinutes(5);
    private int maxNotificationRetries = 3;
    private String smtpHost;
    private int smtpPort;
    private String smtpUsername;
    private String smtpPassword;
    private String slackWebhookUrl;
    private String pagerdutyApiKey;
    private int httpTimeoutSeconds = 10;

    public Duration getEvaluationInterval() { return evaluationInterval; }
    public void setEvaluationInterval(Duration evaluationInterval) { this.evaluationInterval = evaluationInterval; }

    public Duration getDefaultCooldownPeriod() { return defaultCooldownPeriod; }
    public void setDefaultCooldownPeriod(Duration defaultCooldownPeriod) { this.defaultCooldownPeriod = defaultCooldownPeriod; }

    public Duration getDefaultEscalationDelay() { return defaultEscalationDelay; }
    public void setDefaultEscalationDelay(Duration defaultEscalationDelay) { this.defaultEscalationDelay = defaultEscalationDelay; }

    public int getMaxNotificationRetries() { return maxNotificationRetries; }
    public void setMaxNotificationRetries(int maxNotificationRetries) { this.maxNotificationRetries = maxNotificationRetries; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

    public String getSlackWebhookUrl() { return slackWebhookUrl; }
    public void setSlackWebhookUrl(String slackWebhookUrl) { this.slackWebhookUrl = slackWebhookUrl; }

    public String getPagerdutyApiKey() { return pagerdutyApiKey; }
    public void setPagerdutyApiKey(String pagerdutyApiKey) { this.pagerdutyApiKey = pagerdutyApiKey; }

    public int getHttpTimeoutSeconds() { return httpTimeoutSeconds; }
    public void setHttpTimeoutSeconds(int httpTimeoutSeconds) { this.httpTimeoutSeconds = httpTimeoutSeconds; }
}
