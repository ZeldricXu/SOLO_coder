package com.solo.config.module.sidecar;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sidecar")
public class SidecarProperties {

    private Injection injection = new Injection();
    private Resources resources = new Resources();
    private HotReload hotReload = new HotReload();
    private Events events = new Events();

    @Data
    public static class Injection {
        private boolean enabled = true;
        private String strategy = "sidecar";
    }

    @Data
    public static class Resources {
        private String cpu = "500m";
        private String memory = "256Mi";
    }

    @Data
    public static class HotReload {
        private boolean enabled = true;
        private long interval = 10000;
    }

    @Data
    public static class Events {
        private boolean enabled = true;
        private Notification notification = new Notification();

        @Data
        public static class Notification {
            private boolean enabled = true;
            private String channels = "webhook,email";
        }
    }

    private Timeout timeout = new Timeout();

    @Data
    public static class Timeout {
        private long injectTimeoutMs = 5000;
        private long updateTimeoutMs = 3000;
        private long removeTimeoutMs = 3000;
        private long heartbeatTimeoutMs = 2000;
        private long queryTimeoutMs = 2000;
    }
}
