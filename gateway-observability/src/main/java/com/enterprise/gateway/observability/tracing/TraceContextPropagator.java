package com.enterprise.gateway.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class TraceContextPropagator {

    private static final String TRACE_ID_HEADER = "X-B3-TraceId";
    private static final String SPAN_ID_HEADER = "X-B3-SpanId";
    private static final String PARENT_SPAN_ID_HEADER = "X-B3-ParentSpanId";
    private static final String SAMPLED_HEADER = "X-B3-Sampled";

    private final Propagator propagator;

    public TraceContextPropagator(Propagator propagator) {
        this.propagator = propagator;
    }

    public void injectTraceContext(Tracer tracer, HttpHeaders headers) {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            TraceContext context = currentSpan.context();
            headers.set(TRACE_ID_HEADER, context.traceId());
            headers.set(SPAN_ID_HEADER, context.spanId());
            if (context.parentId() != null) {
                headers.set(PARENT_SPAN_ID_HEADER, context.parentId());
            }
            headers.set(SAMPLED_HEADER, context.sampled() != null && context.sampled() ? "1" : "0");

            propagator.inject(context, headers, (h, key, value) -> {
                if (h instanceof HttpHeaders httpHeaders) {
                    httpHeaders.set(key, value);
                }
            });
        }
    }

    public void extractTraceContext(Tracer tracer, HttpHeaders headers) {
        String traceId = headers.getFirst(TRACE_ID_HEADER);
        String spanId = headers.getFirst(SPAN_ID_HEADER);

        if (traceId != null && spanId != null) {
            TraceContext.Builder builder = TraceContext.newBuilder()
                    .traceId(traceId)
                    .spanId(spanId);

            String parentSpanId = headers.getFirst(PARENT_SPAN_ID_HEADER);
            if (parentSpanId != null) {
                builder.parentId(parentSpanId);
            }

            String sampled = headers.getFirst(SAMPLED_HEADER);
            if (sampled != null) {
                builder.sampled("1".equals(sampled));
            }

            TraceContext context = builder.build();
            Span span = tracer.createSpanBuilder()
                    .setParent(context)
                    .name("gateway.received")
                    .start();

            tracer.withSpan(span);
        }
    }
}
