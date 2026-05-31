package com.solocoder.dns.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.solocoder.dns.config")
@EnableConfigurationProperties
public class ConfigModuleConfiguration {
}
