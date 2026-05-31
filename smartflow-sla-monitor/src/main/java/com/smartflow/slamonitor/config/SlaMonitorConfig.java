package com.smartflow.slamonitor.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ComponentScan(basePackages = "com.smartflow.slamonitor")
@EnableScheduling
public class SlaMonitorConfig {
}
