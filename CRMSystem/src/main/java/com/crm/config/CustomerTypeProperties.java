package com.crm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "crm.customer")
public class CustomerTypeProperties {
    
    private List<CustomerType> types = new ArrayList<>();

    @Data
    public static class CustomerType {
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private int priority = 0;
    }
}
