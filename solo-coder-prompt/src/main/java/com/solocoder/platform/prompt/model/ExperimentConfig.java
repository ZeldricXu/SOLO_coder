package com.solocoder.platform.prompt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String experimentId;
    private String name;
    private String description;
    private String promptId;
    private List<Variant> variants;
    private ExperimentStatus status;
    private Map<String, Object> targetingRules;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;

    public enum ExperimentStatus {
        DRAFT, RUNNING, PAUSED, COMPLETED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Variant implements Serializable {
        private static final long serialVersionUID = 1L;
        private String variantId;
        private String versionId;
        private String name;
        private double trafficPercent;
    }
}
