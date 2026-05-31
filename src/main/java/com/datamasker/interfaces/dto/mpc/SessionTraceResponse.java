package com.datamasker.interfaces.dto.mpc;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SessionTraceResponse {

    private String sessionId;
    private List<TraceSpanDto> spans;

    @Data
    public static class TraceSpanDto {
        private String spanId;
        private String operation;
        private LocalDateTime startTime;
        private Long durationMs;
        private boolean success;
    }
}
