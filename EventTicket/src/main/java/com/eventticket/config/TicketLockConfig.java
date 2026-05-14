package com.eventticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "ticket.lock")
public class TicketLockConfig {

    private Map<String, LockTimeoutConfig> timeouts = new HashMap<>();
    private String defaultTicketType = "regular";

    @Data
    public static class LockTimeoutConfig {
        private int lockTimeoutSeconds;
        private int paymentTimeoutMinutes;
        private int autoReleaseDelayMinutes;
        private String description;
    }

    public LockTimeoutConfig getLockConfig(String ticketType) {
        LockTimeoutConfig config = timeouts.get(ticketType);
        if (config == null) {
            config = timeouts.get(defaultTicketType);
        }
        return config;
    }

    public int getLockTimeoutSeconds(String ticketType) {
        LockTimeoutConfig config = getLockConfig(ticketType);
        return config != null ? config.getLockTimeoutSeconds() : 300;
    }

    public int getPaymentTimeoutMinutes(String ticketType) {
        LockTimeoutConfig config = getLockConfig(ticketType);
        return config != null ? config.getPaymentTimeoutMinutes() : 15;
    }

    public int getAutoReleaseDelayMinutes(String ticketType) {
        LockTimeoutConfig config = getLockConfig(ticketType);
        return config != null ? config.getAutoReleaseDelayMinutes() : 5;
    }
}
