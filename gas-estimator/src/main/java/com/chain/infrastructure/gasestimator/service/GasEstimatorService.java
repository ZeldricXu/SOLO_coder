package com.chain.infrastructure.gasestimator.service;

import com.chain.infrastructure.gasestimator.batcher.GasEstimateBatcher;
import com.chain.infrastructure.gasestimator.calculator.GasPriceCalculator;
import com.chain.infrastructure.gasestimator.dto.BatchGasEstimateRequest;
import com.chain.infrastructure.gasestimator.dto.BatchGasEstimateResult;
import com.chain.infrastructure.gasestimator.dto.GasEstimateRequest;
import com.chain.infrastructure.gasestimator.dto.GasEstimateResult;
import com.chain.infrastructure.gasestimator.estimator.GasLimitEstimator;
import com.chain.infrastructure.gasestimator.provider.NetworkStateProvider;
import com.chain.infrastructure.gasestimator.recorder.GasPriceRecorder;
import com.chain.infrastructure.gasestimator.repository.GasHistoryRepository;
import com.chain.infrastructure.persistence.entity.GasHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasEstimatorService {

    private final GasHistoryRepository historyRepository;
    private final GasPriceCalculator priceCalculator;
    private final GasLimitEstimator limitEstimator;
    private final NetworkStateProvider networkStateProvider;
    private final GasPriceRecorder priceRecorder;
    private final GasEstimateBatcher estimateBatcher;

    public Mono<GasEstimateResult> estimateGas(GasEstimateRequest request) {
        int blocksBack = request.getBlocksBack() != null ? request.getBlocksBack() : 100;

        return historyRepository.findRecentHistory(request.getChainType(), blocksBack)
                .flatMap(history -> priceCalculator.calculate(history)
                        .zipWith(limitEstimator.estimate(request))
                        .zipWith(networkStateProvider.getCurrentState(request.getChainType()))
                        .map(tuple -> {
                            var priceResult = tuple.getT1().getT1();
                            var gasLimit = tuple.getT1().getT2();
                            var networkState = tuple.getT2();
                            return buildResult(request.getChainType(), priceResult, gasLimit, networkState);
                        })
                )
                .doOnSuccess(result -> log.info("Gas estimate completed: chain={}, standard={} gwei",
                        request.getChainType(), result.getStandardGasPrice()));
    }

    public Mono<GasEstimateResult> estimateGasBatched(GasEstimateRequest request) {
        return estimateBatcher.estimateWithBatching(request);
    }

    public Mono<BatchGasEstimateResult> estimateBatch(BatchGasEstimateRequest request) {
        long startTime = System.currentTimeMillis();

        return Flux.fromIterable(request.getRequests())
                .flatMap(req -> estimateGas(req)
                        .onErrorResume(e -> {
                            log.warn("Single estimate failed: {}", e.getMessage());
                            return Mono.empty();
                        }))
                .collectList()
                .map(results -> {
                    BatchGasEstimateResult batchResult = new BatchGasEstimateResult();
                    batchResult.setResults(results);
                    batchResult.setTotalCount(request.getRequests().size());
                    batchResult.setSuccessCount(results.size());
                    batchResult.setFailedCount(request.getRequests().size() - results.size());
                    batchResult.setTotalTimeMs(System.currentTimeMillis() - startTime);
                    return batchResult;
                });
    }

    public Mono<List<GasEstimateResult>> estimateGasByChains(List<String> chainTypes, String txType) {
        return Flux.fromIterable(chainTypes)
                .flatMap(chain -> {
                    GasEstimateRequest request = new GasEstimateRequest();
                    request.setChainType(chain);
                    request.setTxType(txType);
                    return estimateGas(request);
                })
                .collectList();
    }

    private GasEstimateResult buildResult(String chainType,
                                            GasPriceCalculator.GasPriceResult priceResult,
                                            Long gasLimit,
                                            NetworkStateProvider.NetworkState networkState) {
        GasEstimateResult result = new GasEstimateResult();
        result.setChainType(chainType);
        result.setSlowGasPrice(priceResult.slowGasPrice());
        result.setStandardGasPrice(priceResult.standardGasPrice());
        result.setFastGasPrice(priceResult.fastGasPrice());
        result.setBaseFee(priceResult.baseFee());
        result.setPriorityFee(priceResult.priorityFee());
        result.setEstimatedGasLimit(gasLimit);
        result.setEstimatedSlowFee(priceResult.slowGasPrice().multiply(BigDecimal.valueOf(gasLimit)));
        result.setEstimatedStandardFee(priceResult.standardGasPrice().multiply(BigDecimal.valueOf(gasLimit)));
        result.setEstimatedFastFee(priceResult.fastGasPrice().multiply(BigDecimal.valueOf(gasLimit)));
        result.setTimestamp(System.currentTimeMillis());
        return result;
    }

    public Mono<GasHistory> recordGasPrice(String chainType, GasEstimateResult result) {
        return priceRecorder.record(chainType, result);
    }
}
