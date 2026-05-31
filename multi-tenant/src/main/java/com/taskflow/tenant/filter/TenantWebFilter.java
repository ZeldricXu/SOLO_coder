package com.taskflow.tenant.filter;

import com.taskflow.common.model.Constants;
import com.taskflow.tenant.context.TenantContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(Constants.TENANT_ID_HEADER);
        String userId = exchange.getRequest().getHeaders().getFirst(Constants.USER_ID_HEADER);
        String traceId = exchange.getRequest().getHeaders().getFirst(Constants.TRACE_ID_HEADER);

        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = Constants.DEFAULT_TENANT_ID;
        }

        exchange.getResponse().getHeaders().set(Constants.TENANT_ID_HEADER, tenantId);
        if (traceId != null) {
            exchange.getResponse().getHeaders().set(Constants.TRACE_ID_HEADER, traceId);
        }

        String finalTenantId = tenantId;
        return chain.filter(exchange)
                .contextWrite(context -> context.putAll(TenantContext.setTenantContext(finalTenantId, userId)));
    }
}
