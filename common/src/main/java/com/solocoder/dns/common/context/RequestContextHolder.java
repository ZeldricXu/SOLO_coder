package com.solocoder.dns.common.context;

import com.solocoder.dns.common.model.RequestContext;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

public class RequestContextHolder {
    private static final String CONTEXT_KEY = "requestContext";

    public static Mono<RequestContext> get() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(CONTEXT_KEY)) {
                return Mono.just(ctx.get(CONTEXT_KEY));
            }
            return Mono.empty();
        });
    }

    public static Context set(RequestContext requestContext) {
        return Context.of(CONTEXT_KEY, requestContext);
    }
}
