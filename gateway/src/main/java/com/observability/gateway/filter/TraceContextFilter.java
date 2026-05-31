package com.observability.gateway.filter;

import com.observability.common.context.RequestContext;
import com.observability.common.context.RequestContextHolder;
import io.micrometer.context.ContextSnapshot;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceContextFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return RequestContextHolder.get()
                .flatMap(context -> {
                    exchange.getAttributes().put("requestContext", context);
                    return ContextSnapshot.captureAll()
                            .wrap(chain.filter(exchange));
                });
    }
}
