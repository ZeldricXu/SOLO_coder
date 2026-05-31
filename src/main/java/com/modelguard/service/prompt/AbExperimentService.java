package com.modelguard.service.prompt;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.dto.request.AbExperimentCreateRequest;
import com.modelguard.dto.response.AbExperimentResponse;
import com.modelguard.dto.response.ExperimentComparisonResponse;
import com.modelguard.entity.AbExperiment;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface AbExperimentService {

    Mono<AbExperimentResponse> createExperiment(AbExperimentCreateRequest request);

    Mono<AbExperimentResponse> getExperiment(String experimentId);

    Mono<AbExperiment> getExperimentEntity(String experimentId);

    Mono<AbExperimentResponse> startExperiment(String experimentId);

    Mono<AbExperimentResponse> pauseExperiment(String experimentId);

    Mono<AbExperimentResponse> stopExperiment(String experimentId);

    Mono<PageResult<AbExperimentResponse>> pageExperiments(String status, int pageNum, int pageSize);

    Mono<String> assignExperimentGroup(String experimentId, String userId);

    Mono<Map<String, Object>> validateExperimentStatus(String experimentId);

    Mono<ExperimentComparisonResponse> compareExperimentResults(String experimentId);

    Mono<AbExperiment> ensureExperimentRunning(String experimentId);
}
