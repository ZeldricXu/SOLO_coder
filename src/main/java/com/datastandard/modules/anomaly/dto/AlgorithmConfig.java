package com.datastandard.modules.anomaly.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmConfig {

    private String algorithmType;
    private BigDecimal sensitivity;
    private BigDecimal threshold;
    private Integer minDataPoints;
    private Integer maxDataPoints;

    private ZScoreConfig zScoreConfig;
    private EwmaConfig ewmaConfig;
    private IsolationForestConfig isolationForestConfig;
    private SeasonalHsdConfig seasonalHsdConfig;

    private List<String> enabledAlgorithms;
    private Boolean ensembleMode;
    private Map<String, BigDecimal> algorithmWeights;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZScoreConfig {
        private BigDecimal zScoreThreshold;
        private Boolean useModifiedZScore;
        private BigDecimal madMultiplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EwmaConfig {
        private BigDecimal alpha;
        private BigDecimal beta;
        private Integer warmupPeriod;
        private BigDecimal controlLimitMultiplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IsolationForestConfig {
        private Integer numTrees;
        private Integer sampleSize;
        private Integer maxDepth;
        private BigDecimal contamination;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeasonalHsdConfig {
        private Integer period;
        private Integer maxAnomalies;
        private BigDecimal significanceLevel;
        private Boolean useAutoCorrelation;
        private Integer robustIters;
    }
}
