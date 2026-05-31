package com.web3platform.gasestimator.controller;

import com.web3platform.gasestimator.model.GasEstimateRequest;
import com.web3platform.gasestimator.model.GasEstimateResponse;
import com.web3platform.gasestimator.model.HistoricalGasData;
import com.web3platform.gasestimator.model.NetworkStatus;
import com.web3platform.gasestimator.service.FeeHistoryCollector;
import com.web3platform.gasestimator.service.GasEstimatorService;
import com.web3platform.gasestimator.service.GasPriceOracle;
import com.web3platform.gasestimator.service.NetworkAnalyzer;
import com.web3platform.gasestimator.service.StatisticsService;
import com.web3platform.persistence.model.entity.GasEstimate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/gas")
@RequiredArgsConstructor
public class GasEstimatorController {

    private final GasEstimatorService gasEstimatorService;
    private final GasPriceOracle gasPriceOracle;
    private final NetworkAnalyzer networkAnalyzer;
    private final FeeHistoryCollector feeHistoryCollector;
    private final StatisticsService statisticsService;

    @PostMapping("/estimate")
    public ResponseEntity<GasEstimateResponse> estimateGas(@RequestBody GasEstimateRequest request) {
        log.info("Received gas estimate request for chain: {}", request.getChainId());
        GasEstimateResponse response = gasEstimatorService.estimateGas(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/estimate/batch")
    public ResponseEntity<List<GasEstimateResponse>> estimateBatch(@RequestBody List<GasEstimateRequest> requests) {
        log.info("Received batch gas estimate request for {} requests", requests.size());
        List<GasEstimateResponse> responses = gasEstimatorService.estimateBatch(requests);
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/price/{chainId}")
    public ResponseEntity<Map<String, Object>> getCurrentGasPrice(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "NORMAL") String speed) {
        log.info("Getting current gas price for chain: {}, speed: {}", chainId, speed);

        BigInteger legacyGasPrice = gasPriceOracle.getLegacyGasPrice(chainId, speed);
        Map<String, BigInteger> eip1559Fees = gasPriceOracle.getEip1559Fees(chainId, speed);
        double confidence = gasPriceOracle.calculateConfidence(chainId, speed);

        Map<String, Object> result = new HashMap<>();
        result.put("chainId", chainId);
        result.put("speed", speed);
        result.put("legacy", Map.of(
                "gasPrice", legacyGasPrice.toString()
        ));
        result.put("eip1559", Map.of(
                "maxFeePerGas", eip1559Fees.get("maxFeePerGas").toString(),
                "maxPriorityFeePerGas", eip1559Fees.get("maxPriorityFeePerGas").toString(),
                "baseFee", eip1559Fees.get("baseFee").toString()
        ));
        result.put("confidence", confidence);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/price/{chainId}/history")
    public ResponseEntity<List<HistoricalGasData>> getGasPriceHistory(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "60") int durationMinutes) {
        log.info("Getting gas price history for chain: {}, duration: {} minutes", chainId, durationMinutes);
        List<HistoricalGasData> history = statisticsService.getGasPriceTrend(chainId, durationMinutes);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/network/{chainId}/status")
    public ResponseEntity<NetworkStatus> getNetworkStatus(@PathVariable String chainId) {
        log.info("Getting network status for chain: {}", chainId);
        NetworkStatus status = networkAnalyzer.analyzeNetworkStatus(chainId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/collect/{chainId}")
    public ResponseEntity<Map<String, Object>> collectFeeHistory(@PathVariable String chainId) {
        log.info("Manual fee history collection triggered for chain: {}", chainId);
        GasEstimate gasEstimate = feeHistoryCollector.collectAndPersist(chainId);
        Map<String, Object> result = new HashMap<>();
        result.put("chainId", chainId);
        result.put("blockNumber", gasEstimate.getBlockNumber());
        result.put("gasPrice", gasEstimate.getGasPrice());
        result.put("baseFee", gasEstimate.getBaseFee());
        result.put("priorityFee", gasEstimate.getPriorityFee());
        result.put("recordedAt", gasEstimate.getRecordedAt());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats/{chainId}/average")
    public ResponseEntity<Map<String, Object>> getAverageGasPrice(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "60") int durationMinutes) {
        log.info("Getting average gas price for chain: {}, duration: {} minutes", chainId, durationMinutes);
        BigInteger avgGasPrice = statisticsService.getAverageGasPrice(chainId, durationMinutes);
        Map<String, Object> result = new HashMap<>();
        result.put("chainId", chainId);
        result.put("durationMinutes", durationMinutes);
        result.put("averageGasPrice", avgGasPrice.toString());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats/{chainId}/min-max")
    public ResponseEntity<Map<String, Object>> getMinMaxGasPrice(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "60") int durationMinutes) {
        log.info("Getting min/max gas price for chain: {}, duration: {} minutes", chainId, durationMinutes);
        Map<String, BigInteger> minMax = statisticsService.getMinMaxGasPrice(chainId, durationMinutes);
        Map<String, Object> result = new HashMap<>();
        result.put("chainId", chainId);
        result.put("durationMinutes", durationMinutes);
        result.put("minGasPrice", minMax.get("min").toString());
        result.put("maxGasPrice", minMax.get("max").toString());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/estimate/gas-limit")
    public ResponseEntity<Map<String, Object>> estimateGasLimit(@RequestBody GasEstimateRequest request) {
        log.info("Estimating gas limit for chain: {}", request.getChainId());
        Long gasLimit = gasEstimatorService.estimateGasLimit(request);
        Map<String, Object> result = new HashMap<>();
        result.put("chainId", request.getChainId());
        result.put("toAddress", request.getToAddress());
        result.put("gasLimit", gasLimit);
        return ResponseEntity.ok(result);
    }
}
