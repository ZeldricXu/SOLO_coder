package com.loganalytics.common.model;

import java.util.ArrayList;
import java.util.List;

public class TraceContext {
    private String traceId;
    private String rootSpanId;
    private List<SpanInfo> spans;
    private long durationMs;
    private boolean hasError;
    private String errorMessage;

    public TraceContext() {
        this.spans = new ArrayList<>();
    }

    public static class SpanInfo {
        private String spanId;
        private String parentSpanId;
        private String serviceName;
        private String operationName;
        private long startTimeMs;
        private long durationMs;
        private boolean hasError;
        private String status;

        public SpanInfo() {}

        public String getSpanId() { return spanId; }
        public void setSpanId(String spanId) { this.spanId = spanId; }

        public String getParentSpanId() { return parentSpanId; }
        public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        public String getOperationName() { return operationName; }
        public void setOperationName(String operationName) { this.operationName = operationName; }

        public long getStartTimeMs() { return startTimeMs; }
        public void setStartTimeMs(long startTimeMs) { this.startTimeMs = startTimeMs; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public boolean isHasError() { return hasError; }
        public void setHasError(boolean hasError) { this.hasError = hasError; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getRootSpanId() { return rootSpanId; }
    public void setRootSpanId(String rootSpanId) { this.rootSpanId = rootSpanId; }

    public List<SpanInfo> getSpans() { return spans; }
    public void setSpans(List<SpanInfo> spans) { this.spans = spans; }
    public void addSpan(SpanInfo span) { this.spans.add(span); }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isHasError() { return hasError; }
    public void setHasError(boolean hasError) { this.hasError = hasError; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
