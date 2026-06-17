package com.loganalytics.pipeline.processor;

import com.loganalytics.common.model.LogEvent;

import java.util.List;

public class ProcessorChain {
    private final List<Processor> processors;

    public ProcessorChain(List<Processor> processors) {
        this.processors = processors;
    }

    public LogEvent process(LogEvent event) {
        LogEvent current = event;
        for (Processor p : processors) {
            if (current == null) return null;
            current = p.process(current);
        }
        return current;
    }

    public List<Processor> getProcessors() {
        return processors;
    }
}
