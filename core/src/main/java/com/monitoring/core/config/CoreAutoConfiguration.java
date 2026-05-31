package com.monitoring.core.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.EnableWebFlux;

@Configuration
@ComponentScan(basePackages = "com.monitoring.core")
@EnableWebFlux
public class CoreAutoConfiguration {
}
