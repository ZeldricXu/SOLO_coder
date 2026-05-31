package com.edgeplatform.config.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ConfigRollbackRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer targetVersion;
    private String changeLog;
}
