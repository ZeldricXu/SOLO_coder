package com.observability.common.context;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.function.Function;

public class RequestContextHolder {

    private static final String CONTEXT_KEY = "requestContext";

    public static Context set(RequestContext context) {
        return Context.of(CONTEXT_KEY, context);
    }

    public static Mono<RequestContext> get() {
        return Mono.deferContextual(contextView -> {
            if (contextView.hasKey(CONTEXT_KEY)) {
                return Mono.just(contextView.get(CONTEXT_KEY));
            }
            return Mono.just(RequestContext.createWithNewTrace());
        });
    }

    public static Function<Context, Context> update(Function<RequestContext, RequestContext> updater) {
        return context -> {
            RequestContext ctx = context.getOrDefault(CONTEXT_KEY, RequestContext.createWithNewTrace());
            return context.put(CONTEXT_KEY, updater.apply(ctx));
        };
    }

    public static <T> Mono<T> withContext(RequestContext context, Mono<T> mono) {
        return mono.contextWrite(set(context));
    }
}
