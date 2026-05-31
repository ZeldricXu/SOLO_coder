package com.datastandard.common.util;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.HashMap;
import java.util.Map;

public class TraceContext {

    private static final TransmittableThreadLocal<Map<String, Object>> CONTEXT = new TransmittableThreadLocal<>();

    private static final String TRACE_ID = "traceId";
    private static final String REQUEST_ID = "requestId";
    private static final String USER_ID = "userId";
    private static final String USER_NAME = "userName";

    public static void setTraceId(String traceId) {
        getContext().put(TRACE_ID, traceId);
    }

    public static String getTraceId() {
        Object value = getContext().get(TRACE_ID);
        return value != null ? value.toString() : null;
    }

    public static void setRequestId(String requestId) {
        getContext().put(REQUEST_ID, requestId);
    }

    public static String getRequestId() {
        Object value = getContext().get(REQUEST_ID);
        return value != null ? value.toString() : null;
    }

    public static void setUserId(String userId) {
        getContext().put(USER_ID, userId);
    }

    public static String getUserId() {
        Object value = getContext().get(USER_ID);
        return value != null ? value.toString() : null;
    }

    public static void setUserName(String userName) {
        getContext().put(USER_NAME, userName);
    }

    public static String getUserName() {
        Object value = getContext().get(USER_NAME);
        return value != null ? value.toString() : null;
    }

    public static void set(String key, Object value) {
        getContext().put(key, value);
    }

    public static Object get(String key) {
        return getContext().get(key);
    }

    public static void remove(String key) {
        getContext().remove(key);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static Map<String, Object> getAll() {
        return new HashMap<>(getContext());
    }

    private static Map<String, Object> getContext() {
        Map<String, Object> context = CONTEXT.get();
        if (context == null) {
            context = new HashMap<>();
            CONTEXT.set(context);
        }
        return context;
    }

    public static void init() {
        if (getTraceId() == null) {
            setTraceId(IdGenerator.generateTraceId());
        }
        if (getRequestId() == null) {
            setRequestId(IdGenerator.generateRequestId());
        }
    }
}
