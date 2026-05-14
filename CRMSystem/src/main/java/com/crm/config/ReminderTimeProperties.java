package com.crm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "crm.reminder")
public class ReminderTimeProperties {
    
    private HighValue highValue = new HighValue();
    private MediumValue mediumValue = new MediumValue();
    private LowValue lowValue = new LowValue();

    @Data
    public static class HighValue {
        private int advanceHours = 48;
        private String description = "高价值客户";
    }

    @Data
    public static class MediumValue {
        private int advanceHours = 36;
        private String description = "中等价值客户";
    }

    @Data
    public static class LowValue {
        private int advanceHours = 24;
        private String description = "低价值客户";
    }
}
