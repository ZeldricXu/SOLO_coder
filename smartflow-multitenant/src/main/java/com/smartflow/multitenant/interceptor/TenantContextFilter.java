package com.smartflow.multitenant.interceptor;

import com.smartflow.common.context.TenantContext;
import com.smartflow.multitenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class TenantContextFilter implements WebFilter {

    private final TenantService tenantService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantIdHeader = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        String userIdHeader = exchange.getRequest().getHeaders().getFirst("X-User-Id");

        if (tenantIdHeader != null && !tenantIdHeader.isEmpty()) {
            try {
                Long tenantId = Long.parseLong(tenantIdHeader);
                Long userId = userIdHeader != null ? Long.parseLong(userIdHeader) : null;
                tenantService.setCurrentTenant(tenantId, userId);
            } catch (NumberFormatException ignored) {
            }
        }

        return chain.filter(exchange)
                .doFinally(signalType -> tenantService.clearCurrentTenant());
    }
}
