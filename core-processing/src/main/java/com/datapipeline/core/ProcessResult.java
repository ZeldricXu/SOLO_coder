package com.datapipeline.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessResult {

    public enum Status {
        SUCCESS,
        TIMEOUT,
        ERROR,
        CANCELLED,
        FALLBACK
    }

    private String requestId;
    private Status status;
    private Object data;
    private String message;
    private String errorDetail;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    @Builder.Default
    private Instant timestamp = Instant.now();
    private long durationMs;

    public static ProcessResult success(String requestId, Object data) {
        return ProcessResult.builder()
                .requestId(requestId)
                .status(Status.SUCCESS)
                .data(data)
                .message("Processing completed successfully")
                .build();
    }

    public static ProcessResult timeout(String requestId, String message) {
        return ProcessResult.builder()
                .requestId(requestId)
                .status(Status.TIMEOUT)
                .message(message)
                .errorDetail(message)
                .build();
    }

    public static ProcessResult error(String requestId, String message, String detail) {
        return ProcessResult.builder()
                .requestId(requestId)
                .status(Status.ERROR)
                .message(message)
                .errorDetail(detail)
                .build();
    }

    public static ProcessResult fallback(String requestId, Object fallbackData, String reason) {
        return ProcessResult.builder()
                .requestId(requestId)
                .status(Status.FALLBACK)
                .data(fallbackData)
                .message("Fallback response: " + reason)
                .build();
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS || status == Status.FALLBACK;
    }

}
