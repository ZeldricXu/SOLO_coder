package com.orchestration.common.tenant;

import com.orchestration.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = request.getHeader("X-Tenant-Id");
        String userId = request.getHeader("X-User-Id");
        String traceId = request.getHeader("X-Trace-Id");

        if (tenantId != null) {
            TenantContext context = new TenantContext();
            context.setTenantId(Long.parseLong(tenantId));
            context.setUserId(userId != null ? Long.parseLong(userId) : null);
            context.setTraceId(traceId);
            TenantContext.set(context);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
