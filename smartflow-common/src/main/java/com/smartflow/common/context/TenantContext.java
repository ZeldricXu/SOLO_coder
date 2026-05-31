package com.smartflow.common.context;

import lombok.Data;
import java.io.Serializable;

@Data
public class TenantContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tenantId;
    private String tenantCode;
    private Long userId;
    private String username;
    private String traceId;

    private static final ThreadLocal<TenantContext> HOLDER = ThreadLocal.withInitial(TenantContext::new);

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
        TenantContext context = get();
        return context != null ? context.getTenantId() : null;
    }

    public static Long getUserId() {
        TenantContext context = get();
        return context != null ? context.getUserId() : null;
    }
}
