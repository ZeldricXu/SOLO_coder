package com.modelguard.service.prompt;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.AbExperimentCreateRequest;
import com.modelguard.dto.request.ExperimentResultRecordRequest;
import com.modelguard.dto.request.PromptVersionCreateRequest;
import com.modelguard.dto.response.AbExperimentResponse;
import com.modelguard.dto.response.AbExperimentResultResponse;
import com.modelguard.dto.response.ExperimentComparisonResponse;
import com.modelguard.dto.response.PromptVersionResponse;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface PromptExperimentFacade {

    Mono<PromptVersionResponse> createPromptVersion(PromptVersionCreateRequest request);

    Mono<PromptVersionResponse> getPromptVersion(String promptId, Integer version);

    Mono<List<PromptVersionResponse>> listPromptVersions(String promptId);

    Mono<PageResult<PromptVersionResponse>> pagePromptVersions(String promptId, int pageNum, int pageSize);

    Mono<String> renderPrompt(String promptId, Integer version, Map<String, Object> variables);

    Mono<AbExperimentResponse> createExperiment(AbExperimentCreateRequest request);

    Mono<AbExperimentResponse> getExperiment(String experimentId);

    Mono<AbExperimentResponse> startExperiment(String experimentId);

    Mono<AbExperimentResponse> pauseExperiment(String experimentId);

    Mono<AbExperimentResponse> stopExperiment(String experimentId);

    Mono<PageResult<AbExperimentResponse>> pageExperiments(String status, int pageNum, int pageSize);

    Mono<String> assignGroup(String experimentId, String userId);

    Mono<AbExperimentResultResponse> recordResult(ExperimentResultRecordRequest request);

    Mono<ExperimentComparisonResponse> compareResults(String experimentId);

    Mono<Map<String, Object>> validateExperiment(String experimentId);
}
