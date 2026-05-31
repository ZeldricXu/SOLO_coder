package com.taskflow.gateway.filter;

import com.taskflow.multi.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 租户上下文过滤器
 * 从请求头中提取租户ID并设置到TenantContext
 */
@Slf4j
@Component
public class TenantContextFilter implements WebFilter {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String DEFAULT_TENANT = "default";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = DEFAULT_TENANT;
        }

        log.debug("Setting tenant context: {}", tenantId);
        TenantContext.setCurrentTenant(tenantId);

        return chain.filter(exchange)
                .doFinally(signalType -> TenantContext.clear());
    }
}
