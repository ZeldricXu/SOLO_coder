package com.taskscheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "taskscheduler.scheduler")
public class SchedulerConfig {

    private int parallelDispatchThreads = 10;
    private int maxParallelTasks = 100;
    private boolean enableParallelDispatch = true;
    private int dispatchQueueSize = 1000;
}
