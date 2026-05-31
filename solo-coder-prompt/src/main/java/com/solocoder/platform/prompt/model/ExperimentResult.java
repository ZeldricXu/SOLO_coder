package com.solocoder.platform.prompt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String resultId;
    private String experimentId;
    private String variantId;
    private String requestId;
    private double score;
    private Map<String, Object> metrics;
    private String feedback;
    private LocalDateTime evaluatedAt;
}
