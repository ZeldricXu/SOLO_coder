package com.taskplatform.common.util;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class ContextHolder {

    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

    private ContextHolder() {}

    public static void set(RequestContext context) {
        CONTEXT.set(context);
    }

    public static RequestContext get() {
        RequestContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = RequestContext.builder()
                    .traceId(IdGenerator.generate("trace_"))
                    .requestTime(LocalDateTime.now())
                    .attributes(new HashMap<>())
                    .build();
            CONTEXT.set(ctx);
        }
        return ctx;
    }

    public static String getTraceId() {
        return get().getTraceId();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    @Data
    @lombok.Builder
    public static class RequestContext {
        private String traceId;
        private String userId;
        private LocalDateTime requestTime;
        private Map<String, Object> attributes;
        private long timeoutMs;

        public void setAttribute(String key, Object value) {
            if (attributes == null) {
                attributes = new HashMap<>();
            }
            attributes.put(key, value);
        }

        public Object getAttribute(String key) {
            return attributes != null ? attributes.get(key) : null;
        }
    }
}
