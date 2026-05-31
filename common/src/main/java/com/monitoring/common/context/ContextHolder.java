package com.monitoring.common.context;

import reactor.util.context.ContextView;

public class ContextHolder {

    private static final String CONTEXT_KEY = "processingContext";

    private ContextHolder() {
    }

    public static reactor.util.context.Context withContext(ProcessingContext context) {
        return reactor.util.context.Context.of(CONTEXT_KEY, context);
    }

    public static ProcessingContext fromContext(ContextView contextView) {
        return contextView.getOrDefault(CONTEXT_KEY, null);
    }
}
