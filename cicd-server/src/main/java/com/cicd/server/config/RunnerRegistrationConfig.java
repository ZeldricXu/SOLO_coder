package com.cicd.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "runner.registration")
public class RunnerRegistrationConfig {

    private boolean requireToken = true;
    private int tokenLength = 32;
    private boolean autoApprove = false;
    private int maxRunnersPerProject = 50;
}
