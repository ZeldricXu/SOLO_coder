package com.scheduler.log.pipeline.pipeline;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class Pipeline<T> {

    private final List<PipelineStage<T, T>> stages;
    private final List<Consumer<T>> sinks;
    private final String name;

    private Pipeline(String name, List<PipelineStage<T, T>> stages, List<Consumer<T>> sinks) {
        this.name = name;
        this.stages = new ArrayList<>(stages);
        this.sinks = new ArrayList<>(sinks);
    }

    public static <T> Builder<T> builder(String name) {
        return new Builder<>(name);
    }

    public Mono<T> process(T input) {
        Mono<T> current = Mono.just(input);
        for (PipelineStage<T, T> stage : stages) {
            current = current.flatMap(value -> {
                if (!stage.shouldProcess(value)) {
                    return Mono.just(value);
                }
                return stage.process(value);
            });
        }
        return current.doOnNext(this::sendToSinks);
    }

    public Flux<T> process(Flux<T> inputs) {
        return inputs.concatMap(this::process);
    }

    private void sendToSinks(T value) {
        for (Consumer<T> sink : sinks) {
            try {
                sink.accept(value);
            } catch (Exception e) {
                // 忽略sink异常，不影响管道流程
            }
        }
    }

    public List<String> getStageNames() {
        return stages.stream().map(PipelineStage::getName).toList();
    }

    public String getName() {
        return name;
    }

    public static class Builder<T> {
        private final String name;
        private final List<PipelineStage<T, T>> stages = new ArrayList<>();
        private final List<Consumer<T>> sinks = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder<T> addStage(PipelineStage<T, T> stage) {
            stages.add(stage);
            return this;
        }

        public Builder<T> addSink(Consumer<T> sink) {
            sinks.add(sink);
            return this;
        }

        public Pipeline<T> build() {
            return new Pipeline<>(name, stages, sinks);
        }
    }
}
