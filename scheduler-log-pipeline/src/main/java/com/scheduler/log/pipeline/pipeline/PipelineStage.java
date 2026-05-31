package com.scheduler.log.pipeline.pipeline;

import reactor.core.publisher.Mono;

public interface PipelineStage<I, O> {
    String getName();
    Mono<O> process(I input);
    default boolean shouldProcess(I input) {
        return true;
    }
}
