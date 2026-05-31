package com.chainetl.modules.gas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.gas.dto.GasEstimateRequest;
import com.chainetl.modules.gas.dto.GasEstimateResponse;
import com.chainetl.modules.gas.dto.GasPriceOracleResponse;
import com.chainetl.modules.gas.mapper.GasEstimateMapper;
import com.chainetl.modules.gas.model.GasEstimate;
import com.chainetl.modules.gas.oracle.GasPriceOracle;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasEstimationService {

    private final GasEstimateMapper gasEstimateMapper;
    private final GasPriceOracle gasPriceOracle;

    private static final Map<String, Long> BASE_GAS_BY_TYPE = Map.of(
            "ETH_TRANSFER", 21000L,
            "ERC20_TRANSFER", 65000L,
            "ERC20_APPROVE", 45000L,
            "CONTRACT_DEPLOY", 1500000L,
            "CONTRACT_CALL", 100000L,
            "SWAP", 150000L,
            "MINT", 120000L,
            "BURN", 80000L,
            "STAKE", 100000L,
            "UNSTAKE", 100000L,
            "DEFAULT", 21000L
    );

    @Transactional
    @Retry(name = "gas", fallbackMethod = "estimateGasFallback")
    @Timed(value = "gas.estimate", description = "Time taken to estimate gas")
    public Mono<GasEstimateResponse> estimateGas(GasEstimateRequest request) {
        return Mono.fromCallable(() -> {
            String chainId = request.getChainId();
            String txType = request.getTransactionType().toUpperCase();

            long baseGas = BASE_GAS_BY_TYPE.getOrDefault(txType, BASE_GAS_BY_TYPE.get("DEFAULT"));

            long dataGas = 0;
            if (request.getData() != null && !request.getData().isEmpty()) {
                dataGas = calculateDataGas(request.getData());
            }

            long estimatedGas = baseGas + dataGas;
            estimatedGas = Math.round(estimatedGas * 1.2);

            GasPriceOracleResponse oracleResponse = gasPriceOracle.getGasPrice(chainId).block();
            Map<String, Object> historicalData = gasPriceOracle.getHistoricalGasData(chainId, 24).block();

            double confidenceLevel = calculateConfidenceLevel(chainId, txType, estimatedGas);

            String estimateId = IdGenerator.generateEstimateId();
            GasEstimate estimate = GasEstimate.builder()
                    .estimateId(estimateId)
                    .chainId(chainId)
                    .transactionType(txType)
                    .estimatedGas(estimatedGas)
                    .gasPriceLow(oracleResponse.getSlowGasPrice())
                    .gasPriceMedium(oracleResponse.getStandardGasPrice())
                    .gasPriceHigh(oracleResponse.getFastGasPrice())
                    .priorityFeeLow(oracleResponse.getSlowPriorityFee())
                    .priorityFeeMedium(oracleResponse.getStandardPriorityFee())
                    .priorityFeeHigh(oracleResponse.getFastPriorityFee())
                    .confidenceLevel(confidenceLevel)
                    .historicalData(historicalData)
                    .createdAt(Instant.now())
                    .build();

            gasEstimateMapper.insert(estimate);
            log.info("Estimated gas: chain={}, type={}, gas={}, confidence={}",
                    chainId, txType, estimatedGas, confidenceLevel);

            return toResponse(estimate);
        });
    }

    public Mono<GasEstimateResponse> getEstimate(String estimateId) {
        return Mono.fromCallable(() -> {
            GasEstimate estimate = gasEstimateMapper.selectById(estimateId);
            if (estimate == null) {
                throw new BusinessException(404, "Gas estimate not found: " + estimateId);
            }
            return toResponse(estimate);
        });
    }

    public Mono<List<GasEstimateResponse>> listEstimates(String chainId, String transactionType, Integer limit) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GasEstimate> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null) {
                wrapper.eq(GasEstimate::getChainId, chainId);
            }
            if (transactionType != null) {
                wrapper.eq(GasEstimate::getTransactionType, transactionType.toUpperCase());
            }
            wrapper.orderByDesc(GasEstimate::getCreatedAt)
                    .last("LIMIT " + (limit != null ? limit : 100));

            List<GasEstimate> estimates = gasEstimateMapper.selectList(wrapper);
            return estimates.stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        });
    }

    public Mono<Map<String, Object>> getGasPriceOracle(String chainId) {
        return gasPriceOracle.getGasPrice(chainId)
                .map(oracle -> Map.<String, Object>of(
                        "chainId", oracle.getChainId(),
                        "baseFee", oracle.getBaseFee(),
                        "gasPrice", Map.of(
                                "low", oracle.getSlowGasPrice(),
                                "medium", oracle.getStandardGasPrice(),
                                "high", oracle.getFastGasPrice()
                        ),
                        "priorityFee", Map.of(
                                "low", oracle.getSlowPriorityFee(),
                                "medium", oracle.getStandardPriorityFee(),
                                "high", oracle.getFastPriorityFee()
                        ),
                        "timestamp", oracle.getTimestamp()
                ));
    }

    public Mono<Map<String, Object>> getHistoricalGasData(String chainId, Integer hours) {
        return gasPriceOracle.getHistoricalGasData(chainId, hours != null ? hours : 24);
    }

    public Mono<Map<String, Long>> calculateTransactionCost(GasEstimateRequest request, String speedLevel) {
        return Mono.fromCallable(() -> {
            GasEstimateResponse estimate = estimateGas(request).block();
            String level = speedLevel != null ? speedLevel.toUpperCase() : "MEDIUM";

            long gasPrice;
            long priorityFee;

            switch (level) {
                case "LOW":
                case "SLOW":
                    gasPrice = estimate.getGasPrice().getLow();
                    priorityFee = estimate.getPriorityFee().getLow();
                    break;
                case "HIGH":
                case "FAST":
                    gasPrice = estimate.getGasPrice().getHigh();
                    priorityFee = estimate.getPriorityFee().getHigh();
                    break;
                case "MEDIUM":
                case "STANDARD":
                default:
                    gasPrice = estimate.getGasPrice().getMedium();
                    priorityFee = estimate.getPriorityFee().getMedium();
                    break;
            }

            long totalGasCost = estimate.getEstimatedGas() * (gasPrice + priorityFee);

            return Map.of(
                    "estimatedGas", estimate.getEstimatedGas(),
                    "gasPrice", gasPrice,
                    "priorityFee", priorityFee,
                    "maxFeePerGas", gasPrice + priorityFee,
                    "totalGasCost", totalGasCost,
                    "totalGasCostEth", totalGasCost / 1_000_000_000_000_000_000.0,
                    "speedLevel", level
            );
        });
    }

    private long calculateDataGas(String data) {
        byte[] bytes = new java.math.BigInteger(data.replace("0x", ""), 16).toByteArray();
        long gas = 0;
        for (byte b : bytes) {
            gas += (b == 0) ? 4 : 16;
        }
        return gas;
    }

    private double calculateConfidenceLevel(String chainId, String txType, long estimatedGas) {
        double baseConfidence = 0.85;
        double typeAdjustment = switch (txType) {
            case "ETH_TRANSFER" -> 0.10;
            case "ERC20_TRANSFER" -> 0.05;
            case "CONTRACT_CALL", "SWAP" -> -0.05;
            case "CONTRACT_DEPLOY" -> -0.15;
            default -> 0.0;
        };
        double gasAdjustment = estimatedGas > 500000 ? -0.10 :
                estimatedGas > 200000 ? -0.05 : 0.0;

        return Math.min(0.99, Math.max(0.50, baseConfidence + typeAdjustment + gasAdjustment));
    }

    private GasEstimateResponse toResponse(GasEstimate estimate) {
        return GasEstimateResponse.builder()
                .estimateId(estimate.getEstimateId())
                .chainId(estimate.getChainId())
                .transactionType(estimate.getTransactionType())
                .estimatedGas(estimate.getEstimatedGas())
                .gasPrice(GasEstimateResponse.GasPriceLevel.builder()
                        .low(estimate.getGasPriceLow())
                        .medium(estimate.getGasPriceMedium())
                        .high(estimate.getGasPriceHigh())
                        .build())
                .priorityFee(GasEstimateResponse.PriorityFeeLevel.builder()
                        .low(estimate.getPriorityFeeLow())
                        .medium(estimate.getPriorityFeeMedium())
                        .high(estimate.getPriorityFeeHigh())
                        .build())
                .confidenceLevel(estimate.getConfidenceLevel())
                .historicalData(estimate.getHistoricalData())
                .createdAt(estimate.getCreatedAt())
                .build();
    }

    private Mono<GasEstimateResponse> estimateGasFallback(GasEstimateRequest request, Exception e) {
        log.error("Estimate gas fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to estimate gas after retries: " + e.getMessage());
    }
}
