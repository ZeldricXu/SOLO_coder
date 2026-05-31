package com.modelguard.service.prompt.impl;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.AbExperimentCreateRequest;
import com.modelguard.dto.request.ExperimentResultRecordRequest;
import com.modelguard.dto.request.PromptVersionCreateRequest;
import com.modelguard.dto.response.AbExperimentResponse;
import com.modelguard.dto.response.AbExperimentResultResponse;
import com.modelguard.dto.response.ExperimentComparisonResponse;
import com.modelguard.dto.response.PromptVersionResponse;
import com.modelguard.service.prompt.AbExperimentResultService;
import com.modelguard.service.prompt.AbExperimentService;
import com.modelguard.service.prompt.PromptExperimentFacade;
import com.modelguard.service.prompt.PromptVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptExperimentFacadeImpl implements PromptExperimentFacade {

    private final PromptVersionService promptVersionService;
    private final AbExperimentService abExperimentService;
    private final AbExperimentResultService abExperimentResultService;

    @Override
    public Mono<PromptVersionResponse> createPromptVersion(PromptVersionCreateRequest request) {
        return promptVersionService.createPromptVersion(request);
    }

    @Override
    public Mono<PromptVersionResponse> getPromptVersion(String promptId, Integer version) {
        return promptVersionService.getPromptVersion(promptId, version);
    }

    @Override
    public Mono<List<PromptVersionResponse>> listPromptVersions(String promptId) {
        return promptVersionService.listPromptVersions(promptId);
    }

    @Override
    public Mono<PageResult<PromptVersionResponse>> pagePromptVersions(String promptId, int pageNum, int pageSize) {
        return promptVersionService.pagePromptVersions(promptId, pageNum, pageSize);
    }

    @Override
    public Mono<String> renderPrompt(String promptId, Integer version, Map<String, Object> variables) {
        return promptVersionService.renderPrompt(promptId, version, variables);
    }

    @Override
    public Mono<AbExperimentResponse> createExperiment(AbExperimentCreateRequest request) {
        return abExperimentService.createExperiment(request);
    }

    @Override
    public Mono<AbExperimentResponse> getExperiment(String experimentId) {
        return abExperimentService.getExperiment(experimentId);
    }

    @Override
    public Mono<AbExperimentResponse> startExperiment(String experimentId) {
        return abExperimentService.startExperiment(experimentId);
    }

    @Override
    public Mono<AbExperimentResponse> pauseExperiment(String experimentId) {
        return abExperimentService.pauseExperiment(experimentId);
    }

    @Override
    public Mono<AbExperimentResponse> stopExperiment(String experimentId) {
        return abExperimentService.stopExperiment(experimentId);
    }

    @Override
    public Mono<PageResult<AbExperimentResponse>> pageExperiments(String status, int pageNum, int pageSize) {
        return abExperimentService.pageExperiments(status, pageNum, pageSize);
    }

    @Override
    public Mono<String> assignGroup(String experimentId, String userId) {
        return abExperimentService.assignExperimentGroup(experimentId, userId);
    }

    @Override
    public Mono<AbExperimentResultResponse> recordResult(ExperimentResultRecordRequest request) {
        return abExperimentResultService.recordResult(request);
    }

    @Override
    public Mono<ExperimentComparisonResponse> compareResults(String experimentId) {
        return abExperimentService.compareExperimentResults(experimentId);
    }

    @Override
    public Mono<Map<String, Object>> validateExperiment(String experimentId) {
        return abExperimentService.validateExperimentStatus(experimentId);
    }
}
