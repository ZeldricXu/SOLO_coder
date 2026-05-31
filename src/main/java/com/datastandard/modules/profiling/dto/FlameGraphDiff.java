package com.datastandard.modules.profiling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlameGraphDiff {

    private String baseSessionId;

    private String targetSessionId;

    private String diffType;

    private Instant createdAt;

    private List<FrameDiff> frameDiffs;

    private Summary summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FrameDiff {
        private String frameName;

        private double basePercentage;

        private double targetPercentage;

        private double percentageDiff;

        private double absoluteDiff;

        private int baseSamples;

        private int targetSamples;

        private int samplesDiff;

        private String changeType;

        private String category;

        private List<String> stackTrace;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private int totalFrames;

        private int increasedFrames;

        private int decreasedFrames;

        private int newFrames;

        private int removedFrames;

        private int unchangedFrames;

        private double maxIncrease;

        private double maxDecrease;

        private double averageChange;

        private Map<String, Integer> changesByCategory;
    }
}
