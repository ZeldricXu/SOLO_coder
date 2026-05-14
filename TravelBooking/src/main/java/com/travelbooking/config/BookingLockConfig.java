package com.travelbooking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "booking.lock")
public class BookingLockConfig {

    private long emergencyTimeoutSeconds = 5;
    private long normalTimeoutSeconds = 30;

    public long getTimeoutSeconds(String urgency) {
        return "EMERGENCY".equalsIgnoreCase(urgency) ? emergencyTimeoutSeconds : normalTimeoutSeconds;
    }

    public long getTimeoutMillis(String urgency) {
        return TimeUnit.SECONDS.toMillis(getTimeoutSeconds(urgency));
    }

    public TimeUnit getDefaultTimeUnit() {
        return TimeUnit.SECONDS;
    }

    public LockTimeoutConfig getEmergencyConfig() {
        return new LockTimeoutConfig("EMERGENCY", emergencyTimeoutSeconds, TimeUnit.SECONDS);
    }

    public LockTimeoutConfig getNormalConfig() {
        return new LockTimeoutConfig("NORMAL", normalTimeoutSeconds, TimeUnit.SECONDS);
    }

    public LockTimeoutConfig getConfigByUrgency(String urgency) {
        return "EMERGENCY".equalsIgnoreCase(urgency) ? getEmergencyConfig() : getNormalConfig();
    }

    @Data
    public static class LockTimeoutConfig {
        private final String urgency;
        private final long timeout;
        private final TimeUnit unit;

        public LockTimeoutConfig(String urgency, long timeout, TimeUnit unit) {
            this.urgency = urgency;
            this.timeout = timeout;
            this.unit = unit;
        }

        public long toMillis() {
            return unit.toMillis(timeout);
        }
    }
}
