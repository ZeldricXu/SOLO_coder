package com.loganalytics.pipeline.processor;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.pipeline.filter.LogFilter;

import java.util.Map;

public class FilterProcessor implements Processor {
    private LogFilter filter;

    public FilterProcessor() {
        this.filter = new LogFilter();
    }

    public FilterProcessor(LogFilter filter) {
        this.filter = filter;
    }

    @Override
    public String getType() {
        return "filter";
    }

    @Override
    public LogEvent process(LogEvent event) {
        if (filter.accept(event)) {
            return event;
        }
        return null;
    }

    @Override
    public void configure(Map<String, String> params) {
        filter = new LogFilter();
        String excludedLevels = params.get("excludedLevels");
        String excludeHealthChecks = params.get("excludeHealthChecks");

        com.loganalytics.pipeline.config.PipelineConfig.FilterConfig filterConfig =
                new com.loganalytics.pipeline.config.PipelineConfig.FilterConfig(
                        excludedLevels != null ? java.util.Set.of(excludedLevels.split(",")) : null,
                        null,
                        null,
                        "true".equalsIgnoreCase(excludeHealthChecks)
                );
        filter.configure(filterConfig);
    }

    @Override
    public void close() {
    }

    public LogFilter getFilter() {
        return filter;
    }
}
