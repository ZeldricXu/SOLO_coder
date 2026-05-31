package com.taskflow.logging.context;

import com.taskflow.common.utils.IdGenerator;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

public class LogContext {

    private static final String TRACE_ID = "traceId";
    private static final String TENANT_ID = "tenantId";
    private static final String USER_ID = "userId";
    private static final String MODULE = "module";

    public static String getTraceId() {
        String traceId = MDC.get(TRACE_ID);
        if (traceId == null) {
            traceId = IdGenerator.generateTraceId();
            MDC.put(TRACE_ID, traceId);
        }
        return traceId;
    }

    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static void setTenantId(String tenantId) {
        MDC.put(TENANT_ID, tenantId);
    }

    public static void setUserId(String userId) {
        MDC.put(USER_ID, userId);
    }

    public static void setModule(String module) {
        MDC.put(MODULE, module);
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(TENANT_ID);
        MDC.remove(USER_ID);
        MDC.remove(MODULE);
    }

    public static Map<String, String> getContext() {
        return MDC.getCopyOfContextMap();
    }

    public static void setContext(Map<String, String> context) {
        if (context != null) {
            MDC.setContextMap(context);
        }
    }

    public static <T> Callable<T> wrapWithContext(Callable<T> callable) {
        Map<String, String> context = getContext();
        return () -> {
            try {
                setContext(context);
                return callable.call();
            } finally {
                clear();
            }
        };
    }

    public static Runnable wrapWithContext(Runnable runnable) {
        Map<String, String> context = getContext();
        return () -> {
            try {
                setContext(context);
                runnable.run();
            } finally {
                clear();
            }
        };
    }
}
