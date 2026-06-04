package com.cicd.runner.executor;

import com.cicd.grpc.PipelineJob;
import com.cicd.grpc.PipelineStep;

import java.util.Map;
import java.util.function.Consumer;

public interface StepExecutor {
    boolean execute(PipelineStep step, PipelineJob job, Map<String, String> env, Consumer<String> logConsumer) throws Exception;
}
