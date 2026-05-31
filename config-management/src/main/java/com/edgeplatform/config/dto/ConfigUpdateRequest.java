package com.edgeplatform.config.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class ConfigUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Map<String, Object> parameters;
    private Boolean enabled;
    private String description;
    private String changeLog;
}
