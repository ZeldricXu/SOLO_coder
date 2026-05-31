package com.didauth.core.context;

import reactor.util.context.Context;

public class RequestContextHolder {

    private static final String CONTEXT_KEY = "requestContext";

    public static Context set(Context context, RequestContext requestContext) {
        return context.put(CONTEXT_KEY, requestContext);
    }

    public static RequestContext get(reactor.util.context.ContextView contextView) {
        return contextView.getOrDefault(CONTEXT_KEY, null);
    }
}
