package com.solocoder.domain.port;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface PromptExperimentPort {

    Mono<String> createPromptVersion(String promptName, String content, Map<String, Object> variables,
                                      String createdBy, String description);

    Mono<String> getPromptContent(String promptName, String version);

    Flux<Map<String, Object>> listPromptVersions(String promptName);

    Mono<String> createAbExperiment(String experimentName, String promptName,
                                     List<String> versions, List<Double> trafficSplit,
                                     Instant startTime, Instant endTime);

    Mono<Void> recordExperimentResult(String experimentId, String version,
                                       String requestId, Map<String, Object> metrics);

    Mono<Map<String, Object>> getExperimentResults(String experimentId);

    Mono<Map<String, Object>> compareVersions(String promptName, List<String> versions, List<String> metrics);

    Mono<Void> setDefaultVersion(String promptName, String version);

    Mono<Void> rollbackPrompt(String promptName, String targetVersion);
}
