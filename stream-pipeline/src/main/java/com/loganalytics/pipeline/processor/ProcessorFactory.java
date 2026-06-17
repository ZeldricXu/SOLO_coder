package com.loganalytics.pipeline.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProcessorFactory {
    private static final Logger log = LoggerFactory.getLogger(ProcessorFactory.class);

    private static final Map<String, Class<? extends Processor>> REGISTRY = Map.of(
            "parse", ParseProcessor.class,
            "filter", FilterProcessor.class,
            "enrich", EnrichProcessor.class,
            "route", RouteProcessor.class
    );

    public static Processor create(String type, Map<String, String> params) {
        Class<? extends Processor> clazz = REGISTRY.get(type);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown processor type: " + type);
        }
        try {
            Processor processor = clazz.getDeclaredConstructor().newInstance();
            if (params != null && !params.isEmpty()) {
                processor.configure(params);
            }
            return processor;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create processor of type: " + type, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static ProcessorChain createChain(List<Map<String, Object>> config) {
        List<Processor> processors = new ArrayList<>();
        for (Map<String, Object> entry : config) {
            String type = (String) entry.get("type");
            Map<String, String> params = new HashMap<>();
            Object paramsObj = entry.get("params");
            if (paramsObj instanceof Map) {
                Map<String, Object> rawParams = (Map<String, Object>) paramsObj;
                for (Map.Entry<String, Object> paramEntry : rawParams.entrySet()) {
                    params.put(paramEntry.getKey(), String.valueOf(paramEntry.getValue()));
                }
            }
            Processor processor = create(type, params);
            processors.add(processor);
            log.info("Created processor: {} with {} params", type, params.size());
        }
        return new ProcessorChain(processors);
    }
}
