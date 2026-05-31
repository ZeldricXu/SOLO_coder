package com.edgeplatform.config.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String configId;
    private String namespace;
    private Integer version;
    private Map<String, Object> parameters;
    private Boolean enabled;
    private LocalDateTime appliedAt;
    private String description;
    private String changeLog;
}
