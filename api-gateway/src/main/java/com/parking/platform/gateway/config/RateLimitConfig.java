package com.parking.platform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitConfig {

    private boolean enabled = true;
    private DefaultLimit defaultLimit = new DefaultLimit();
    private HeaderBased headerBased = new HeaderBased();
    private IpBased ipBased = new IpBased();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DefaultLimit getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(DefaultLimit defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public HeaderBased getHeaderBased() {
        return headerBased;
    }

    public void setHeaderBased(HeaderBased headerBased) {
        this.headerBased = headerBased;
    }

    public IpBased getIpBased() {
        return ipBased;
    }

    public void setIpBased(IpBased ipBased) {
        this.ipBased = ipBased;
    }

    public static class DefaultLimit {
        private long perMinute = 100;
        private long perHour = 1000;

        public long getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(long perMinute) {
            this.perMinute = perMinute;
        }

        public long getPerHour() {
            return perHour;
        }

        public void setPerHour(long perHour) {
            this.perHour = perHour;
        }
    }

    public static class HeaderBased {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class IpBased {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
