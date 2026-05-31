package com.observability.logpipe.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class LogPipelineConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String pipelineId;
    private String name;
    private SourceConfig source;
    private ParserConfig parser;
    private List<FilterConfig> filters;
    private List<RouterConfig> routers;
    private boolean enabled;

    @Data
    public static class SourceConfig implements Serializable {
        private String type;
        private Map<String, Object> config;
    }

    @Data
    public static class ParserConfig implements Serializable {
        private String type;
        private String pattern;
        private Map<String, Object> config;
    }

    @Data
    public static class FilterConfig implements Serializable {
        private String type;
        private String condition;
        private Map<String, Object> config;
    }

    @Data
    public static class RouterConfig implements Serializable {
        private String type;
        private String destination;
        private Map<String, Object> config;
    }
}
