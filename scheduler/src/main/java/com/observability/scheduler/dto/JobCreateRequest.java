package com.observability.scheduler.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class JobCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String cronExpression;
    private String jobType;
    private Map<String, Object> jobParams;
}
