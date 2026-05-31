package com.tracetopology.common.exception;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class TopologyConsistencyException extends BaseException {

    private final String namespace;
    private final String phase;
    private final int expectedNodes;
    private final int actualNodes;
    private final int expectedEdges;
    private final int actualEdges;
    private final Map<String, Object> recoveryInfo;

    private TopologyConsistencyException(Builder builder) {
        super(builder.code, builder.message, builder.cause);
        this.namespace = builder.namespace;
        this.phase = builder.phase;
        this.expectedNodes = builder.expectedNodes;
        this.actualNodes = builder.actualNodes;
        this.expectedEdges = builder.expectedEdges;
        this.actualEdges = builder.actualEdges;
        this.recoveryInfo = builder.recoveryInfo;
    }

    public static Builder builder(String code, String message) {
        return new Builder(code, message);
    }

    public boolean isRecoverable() {
        return recoveryInfo != null && !recoveryInfo.isEmpty();
    }

    public static class Builder {
        private final String code;
        private final String message;
        private String namespace;
        private String phase;
        private int expectedNodes;
        private int actualNodes;
        private int expectedEdges;
        private int actualEdges;
        private Throwable cause;
        private final Map<String, Object> recoveryInfo = new HashMap<>();

        public Builder(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder phase(String phase) {
            this.phase = phase;
            return this;
        }

        public Builder expectedNodes(int expectedNodes) {
            this.expectedNodes = expectedNodes;
            return this;
        }

        public Builder actualNodes(int actualNodes) {
            this.actualNodes = actualNodes;
            return this;
        }

        public Builder expectedEdges(int expectedEdges) {
            this.expectedEdges = expectedEdges;
            return this;
        }

        public Builder actualEdges(int actualEdges) {
            this.actualEdges = actualEdges;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder recoveryInfo(String key, Object value) {
            this.recoveryInfo.put(key, value);
            return this;
        }

        public TopologyConsistencyException build() {
            return new TopologyConsistencyException(this);
        }
    }
}
