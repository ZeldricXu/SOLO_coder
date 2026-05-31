package com.orchestration.common.context;

import lombok.Data;
import java.io.Serializable;

@Data
public class TenantContext implements Serializable {

    private Long tenantId;
    private String tenantCode;
    private Long userId;
    private String username;
    private String traceId;

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    public static TenantContext get() {
        return HOLDER.get();
    }

    public static void set(TenantContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long getTenantId() {
        TenantContext context = HOLDER.get();
        return context != null ? context.getTenantId() : null;
    }

    public static Long getUserId() {
        TenantContext context = HOLDER.get();
        return context != null ? context.getUserId() : null;
    }

    public static String getTraceId() {
        TenantContext context = HOLDER.get();
        return context != null ? context.getTraceId() : null;
    }
}
