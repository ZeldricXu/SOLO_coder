package com.solocoder.platform.prompt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentComparison implements Serializable {

    private static final long serialVersionUID = 1L;

    private String experimentId;
    private List<VariantStats> variantStats;
    private String winner;
    private double confidence;
    private String recommendation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariantStats implements Serializable {
        private static final long serialVersionUID = 1L;
        private String variantId;
        private String variantName;
        private int sampleSize;
        private double meanScore;
        private double stdDeviation;
        private double minScore;
        private double maxScore;
    }
}
