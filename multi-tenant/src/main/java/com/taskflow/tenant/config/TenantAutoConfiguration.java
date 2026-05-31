package com.taskflow.tenant.config;

import com.taskflow.tenant.filter.TenantWebFilter;
import com.taskflow.tenant.service.TenantService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TenantAutoConfiguration {

    @Bean
    public TenantWebFilter tenantWebFilter() {
        return new TenantWebFilter();
    }

    @Bean
    public TenantService tenantService() {
        return new TenantService();
    }
}
