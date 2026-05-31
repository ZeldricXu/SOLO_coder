package com.taskflow.core.resource.config;

import com.taskflow.core.resource.api.ResourceService;
import com.taskflow.core.resource.internal.DefaultResourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心资源模块自动配置
 */
@Configuration
public class CoreResourceAutoConfiguration {

    @Bean
    public ResourceService resourceService() {
        return new DefaultResourceService();
    }
}
