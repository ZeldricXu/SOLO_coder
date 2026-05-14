package com.invoice.mgmt.type.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "invoice.types")
public class InvoiceTypeProperties {
    private boolean enableConfig = true;
    private boolean autoSyncToDb = true;
    private List<InvoiceTypeConfig> configs = new ArrayList<>();

    @Data
    public static class InvoiceTypeConfig {
        private String code;
        private String name;
        private BigDecimal taxRate;
        private boolean enabled = true;
        private String description;
    }
}
