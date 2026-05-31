package com.chain.infrastructure.gasestimator.batcher;

import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import com.chain.infrastructure.gasestimator.dto.GasEstimateResult;
import com.chain.infrastructure.gasestimator.service.GasEstimatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GasEstimateBatcher {

    private final GasEstimatorService gasEstimatorService;
    private RequestBatcher batcher;

    @PostConstruct
    public void init() {
        batcher = new RequestBatcher(50, Duration.ofMillis(100));
        batcher.startAutoFlush(this::processBatch);
    }

    public Mono<GasEstimateResult> estimateWithBatching(GasEstimateRequest request) {
        return batcher.submit(request, this::processBatch);
    }

    private Mono<Map<GasEstimateRequest, GasEstimateResult>> processBatch(List<GasEstimateRequest> requests) {
        log.debug("Processing batch of {} gas estimate requests", requests.size());
        return Flux.fromIterable(requests)
                .flatMap(request -> gasEstimatorService.estimateGas(request)
                        .map(result -> Map.entry(request, result)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
