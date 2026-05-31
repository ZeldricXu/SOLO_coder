package com.observability.profiling.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ProfileResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String profileId;
    private String type;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long durationMs;
    private List<StackFrame> stackFrames;
    private Map<String, Object> metadata;
    private String flameGraphData;
    private String status;
}
