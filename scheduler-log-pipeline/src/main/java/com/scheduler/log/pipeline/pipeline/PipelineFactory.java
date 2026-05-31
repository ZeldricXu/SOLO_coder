package com.scheduler.log.pipeline.pipeline;

import com.scheduler.log.pipeline.model.LogEntry;
import com.scheduler.log.pipeline.processor.LogProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class PipelineFactory {

    private final List<LogProcessor> processors;

    public Pipeline<LogEntry> createLogPipeline(String name) {
        Pipeline.Builder<LogEntry> builder = Pipeline.builder(name);

        for (LogProcessor processor : processors) {
            builder.addStage(new ProcessorStageAdapter<>(processor));
        }

        return builder.build();
    }

    public Pipeline<LogEntry> createLogPipeline(String name, List<Consumer<LogEntry>> sinks) {
        Pipeline.Builder<LogEntry> builder = Pipeline.builder(name);

        for (LogProcessor processor : processors) {
            builder.addStage(new ProcessorStageAdapter<>(processor));
        }

        for (Consumer<LogEntry> sink : sinks) {
            builder.addSink(sink);
        }

        return builder.build();
    }

    private static class ProcessorStageAdapter<T> implements PipelineStage<T, T> {
        private final LogProcessor processor;

        ProcessorStageAdapter(LogProcessor processor) {
            this.processor = processor;
        }

        @Override
        public String getName() {
            return processor.getName();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Mono<T> process(T input) {
            return (Mono<T>) processor.process((LogEntry) input);
        }
    }
}
