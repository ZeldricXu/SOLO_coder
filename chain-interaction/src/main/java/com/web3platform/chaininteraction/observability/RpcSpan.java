package com.web3platform.chaininteraction.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcSpan {

    private String traceId;
    private String chainId;
    private String method;
    private long startTime;
    private Long endTime;
    private String status;
    private String error;
    @Builder.Default
    private Map<String, String> metadata = new ConcurrentHashMap<>();

    public long getDurationMs() {
        if (endTime == null) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }

    public boolean isActive() {
        return endTime == null;
    }
}
