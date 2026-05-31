package com.monitoring.profiler.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileSample {

    private String profileId;

    private String type;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant timestamp;

    private Long durationMs;

    private Integer sampleCount;

    private String threadName;

    private Long threadId;

    private List<StackFrame> stackTrace;

    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StackFrame {
        private String className;
        private String methodName;
        private String fileName;
        private Integer lineNumber;
        private Long samples;
        private Long selfTimeNs;
        private Long totalTimeNs;
    }
}
