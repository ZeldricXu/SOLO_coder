package com.modelguard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.dto.AbExperimentDTO;
import com.modelguard.dto.ExperimentResultRecordDTO;
import com.modelguard.dto.PromptVersionDTO;
import com.modelguard.entity.AbExperiment;
import com.modelguard.entity.AbExperimentResult;
import com.modelguard.entity.PromptVersion;
import reactor.core.publisher.Mono;
import java.util.List;

public interface PromptService {

    Mono<PromptVersion> createPromptVersion(PromptVersionDTO dto);

    Mono<PromptVersion> getPromptVersion(String promptId, Integer version);

    Mono<List<PromptVersion>> listPromptVersions(String promptId);

    Mono<PageResult<PromptVersion>> pagePromptVersions(String promptId, int pageNum, int pageSize);

    Mono<PromptVersion> getLatestPromptVersion(String promptId);

    Mono<String> renderPrompt(String promptId, Integer version, java.util.Map<String, Object> variables);

    Mono<AbExperiment> createAbExperiment(AbExperimentDTO dto);

    Mono<AbExperiment> getAbExperiment(String experimentId);

    Mono<AbExperiment> startAbExperiment(String experimentId);

    Mono<AbExperiment> pauseAbExperiment(String experimentId);

    Mono<AbExperiment> stopAbExperiment(String experimentId);

    Mono<PageResult<AbExperiment>> pageAbExperiments(String status, int pageNum, int pageSize);

    Mono<AbExperimentResult> recordExperimentResult(ExperimentResultRecordDTO dto);

    Mono<List<AbExperimentResult>> getExperimentResults(String experimentId);

    Mono<java.util.Map<String, Object>> compareExperimentResults(String experimentId);

    Mono<String> assignExperimentGroup(String experimentId, String userId);
}
