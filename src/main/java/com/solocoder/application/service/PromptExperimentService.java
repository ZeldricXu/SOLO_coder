package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.PromptExperimentPort;
import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PromptExperimentService {

    private final PromptExperimentPort promptExperimentPort;
    private final StructuredLoggerPort logger;

    public Mono<ApiResponse<String>> createPromptVersion(String promptName, String content,
                                                          Map<String, Object> variables,
                                                          String createdBy, String description) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "promptName", promptName,
                "createdBy", createdBy
        );
        logger.info("创建Prompt版本", context);

        return promptExperimentPort.createPromptVersion(promptName, content, variables, createdBy, description)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("创建Prompt版本失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }

    public Mono<ApiResponse<String>> getPromptContent(String promptName, String version) {
        return promptExperimentPort.getPromptContent(promptName, version)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "Prompt不存在")));
    }

    public Mono<ApiResponse<Flux<Map<String, Object>>>> listPromptVersions(String promptName) {
        return Mono.just(ApiResponse.success(
                promptExperimentPort.listPromptVersions(promptName)
        ));
    }

    public Mono<ApiResponse<String>> createAbExperiment(String experimentName, String promptName,
                                                         List<String> versions,
                                                         List<Double> trafficSplit,
                                                         Instant startTime, Instant endTime) {
        Map<String, Object> context = Map.of(
                "experimentName", experimentName,
                "promptName", promptName,
                "versionCount", versions.size()
        );
        logger.info("创建AB实验", context);

        return promptExperimentPort.createAbExperiment(
                        experimentName, promptName, versions, trafficSplit, startTime, endTime)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("创建AB实验失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }

    public Mono<ApiResponse<Void>> recordExperimentResult(String experimentId, String version,
                                                           String requestId,
                                                           Map<String, Object> metrics) {
        return promptExperimentPort.recordExperimentResult(experimentId, version, requestId, metrics)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Map<String, Object>>> getExperimentResults(String experimentId) {
        return promptExperimentPort.getExperimentResults(experimentId)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "实验不存在")));
    }

    public Mono<ApiResponse<Map<String, Object>>> compareVersions(String promptName,
                                                                   List<String> versions,
                                                                   List<String> metrics) {
        return promptExperimentPort.compareVersions(promptName, versions, metrics)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Void>> setDefaultVersion(String promptName, String version) {
        return promptExperimentPort.setDefaultVersion(promptName, version)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Void>> rollbackPrompt(String promptName, String targetVersion) {
        Map<String, Object> context = Map.of(
                "promptName", promptName,
                "targetVersion", targetVersion
        );
        logger.info("回滚Prompt版本", context);

        return promptExperimentPort.rollbackPrompt(promptName, targetVersion)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("回滚Prompt版本失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }
}
