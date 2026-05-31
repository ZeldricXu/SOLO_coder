package com.solocoder.platform.featurestore.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsistencyReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String featureId;
    private int totalChecks;
    private int passedChecks;
    private int failedChecks;
    private double consistencyRate;
    private List<ConsistencyViolation> violations;
    private LocalDateTime checkedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsistencyViolation implements Serializable {
        private static final long serialVersionUID = 1L;
        private String entityId;
        private Object onlineValue;
        private Object offlineValue;
        private String description;
    }
}
