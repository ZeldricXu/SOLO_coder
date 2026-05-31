package com.contractai.common.context;

import com.contractai.common.exception.BusinessException;

public class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    public static void setTenantId(Long tenantId) {
        TENANT_ID_HOLDER.set(tenantId);
    }

    public static Long getTenantId() {
        Long tenantId = TENANT_ID_HOLDER.get();
        if (tenantId == null) {
            throw new BusinessException(401, "租户信息未设置");
        }
        return tenantId;
    }

    public static Long getTenantIdSafe() {
        return TENANT_ID_HOLDER.get();
    }

    public static void clearTenantId() {
        TENANT_ID_HOLDER.remove();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    public static void clearTraceId() {
        TRACE_ID_HOLDER.remove();
    }

    public static void clear() {
        clearTenantId();
        clearTraceId();
    }
}
