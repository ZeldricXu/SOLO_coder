package com.llmgateway.common.context;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class RequestContext implements Serializable {

    private String traceId;
    private String userId;
    private String tenantId;
    private LocalDateTime startTime;
    private Map<String, Object> attributes = new HashMap<>();

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    public static RequestContext init(String traceId) {
        RequestContext ctx = new RequestContext();
        ctx.setTraceId(traceId);
        ctx.setStartTime(LocalDateTime.now());
        HOLDER.set(ctx);
        return ctx;
    }

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}
