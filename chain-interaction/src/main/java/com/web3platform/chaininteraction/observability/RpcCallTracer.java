package com.web3platform.chaininteraction.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RpcCallTracer {

    private final ConcurrentHashMap<String, RpcSpan> activeSpans = new ConcurrentHashMap<>();

    public RpcSpan startSpan(String chainId, String method) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        RpcSpan span = RpcSpan.builder()
                .traceId(traceId)
                .chainId(chainId)
                .method(method)
                .startTime(System.currentTimeMillis())
                .build();
        activeSpans.put(traceId, span);
        log.debug("Started RPC span: traceId={}, chainId={}, method={}", traceId, chainId, method);
        return span;
    }

    public void endSpan(RpcSpan span, String status) {
        span.setEndTime(System.currentTimeMillis());
        span.setStatus(status);
        activeSpans.remove(span.getTraceId());

        if ("FAILED".equals(status)) {
            log.warn("RPC span completed: traceId={}, chainId={}, method={}, status={}, durationMs={}{}",
                    span.getTraceId(), span.getChainId(), span.getMethod(), status,
                    span.getDurationMs(),
                    span.getError() != null ? ", error=" + span.getError() : "");
        } else {
            log.debug("RPC span completed: traceId={}, chainId={}, method={}, status={}, durationMs={}",
                    span.getTraceId(), span.getChainId(), span.getMethod(), status, span.getDurationMs());
        }
    }

    public List<RpcSpan> getActiveSpans() {
        return Collections.unmodifiableList(new ArrayList<>(activeSpans.values()));
    }
}
