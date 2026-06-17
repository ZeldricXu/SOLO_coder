package com.loganalytics.pipeline.processor;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.pipeline.enrich.LogEnricher;

import java.util.Map;

public class EnrichProcessor implements Processor {
    private LogEnricher enricher;

    public EnrichProcessor() {
        this.enricher = new LogEnricher();
    }

    public EnrichProcessor(LogEnricher enricher) {
        this.enricher = enricher;
    }

    @Override
    public String getType() {
        return "enrich";
    }

    @Override
    public LogEvent process(LogEvent event) {
        return enricher.enrich(event);
    }

    @Override
    public void configure(Map<String, String> params) {
        enricher = new LogEnricher();
        enricher.initialize(params);
    }

    @Override
    public void close() {
    }

    public LogEnricher getEnricher() {
        return enricher;
    }
}
