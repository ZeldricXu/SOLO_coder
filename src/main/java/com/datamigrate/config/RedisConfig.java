package com.datamigrate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "datamigrate.redis")
public class RedisConfig {

    private boolean enabled = false;
    private String host = "localhost";
    private int port = 6379;
    private String password;
    private int database = 0;
    private int maxIdle = 8;
    private int minIdle = 0;
    private int maxTotal = 8;
    private long maxWaitMillis = -1L;
    private String queueKeyPrefix = "datamigrate:write_queue:";
    private String progressKeyPrefix = "datamigrate:progress:";
    private String checkpointKeyPrefix = "datamigrate:checkpoint:";
}
