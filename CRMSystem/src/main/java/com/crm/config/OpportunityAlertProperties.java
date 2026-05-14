package com.crm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "crm.opportunity.alert")
public class OpportunityAlertProperties {
    
    private LargeAmount largeAmount = new LargeAmount();
    private MediumAmount mediumAmount = new MediumAmount();
    private SmallAmount smallAmount = new SmallAmount();

    @Data
    public static class LargeAmount {
        private double threshold = 100000.0;
        private int alertDays = 3;
        private String description = "大额机会";
    }

    @Data
    public static class MediumAmount {
        private double threshold = 50000.0;
        private int alertDays = 5;
        private String description = "中等额度机会";
    }

    @Data
    public static class SmallAmount {
        private double threshold = 0.0;
        private int alertDays = 7;
        private String description = "小额机会";
    }
}
