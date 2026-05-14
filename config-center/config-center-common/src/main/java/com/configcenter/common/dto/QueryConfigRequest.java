package com.configcenter.common.dto;

import com.configcenter.common.enums.Environment;
import lombok.Data;

@Data
public class QueryConfigRequest {
    
    private String application;

    private Environment environment;

    private String groupId;

    private String configKey;
}
