package com.orchestration.common.context;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class RequestContext implements Serializable {

    private String traceId;
    private Long startTime;
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    public static RequestContext get() {
        RequestContext context = HOLDER.get();
        if (context == null) {
            context = new RequestContext();
            context.setStartTime(System.currentTimeMillis());
            HOLDER.set(context);
        }
        return context;
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
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

    public <T> T getAttribute(String key, Class<T> clazz) {
        Object value = attributes.get(key);
        return value != null && clazz.isInstance(value) ? clazz.cast(value) : null;
    }
}
