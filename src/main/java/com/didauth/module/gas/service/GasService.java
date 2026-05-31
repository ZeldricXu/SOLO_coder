package com.didauth.module.gas.service;

import com.didauth.common.enums.ChainType;
import com.didauth.core.entity.GasEstimate;
import com.didauth.core.entity.GasHistory;
import com.didauth.core.mapper.GasEstimateMapper;
import com.didauth.core.mapper.GasHistoryMapper;
import com.didauth.module.gas.dto.GasEstimateResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasService {

    private final GasEstimateMapper gasEstimateMapper;
    private final GasHistoryMapper gasHistoryMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, GasEstimateResponse> currentEstimates = new HashMap<>();

    public Mono<GasEstimateResponse> estimateGas(String chainType, String priorityLevel) {
        ChainType type = ChainType.fromCode(chainType);
        String priority = priorityLevel != null ? priorityLevel.toUpperCase() : "STANDARD";

        return Mono.fromCallable(() -> {
            GasEstimateResponse cached = currentEstimates.get(type.getCode() + "_" + priority);
            if (cached != null && System.currentTimeMillis() - cached.getTimestamp() < 60000) {
                return cached;
            }

            GasEstimateResponse estimate = calculateGasEstimate(type, priority);
            currentEstimates.put(type.getCode() + "_" + priority, estimate);

            GasEstimate entity = new GasEstimate();
            entity.setChainType(type.getCode());
            entity.setPriorityLevel(priority);
            entity.setGasPrice(estimate.getGasPrice());
            entity.setMaxFeePerGas(estimate.getMaxFeePerGas());
            entity.setMaxPriorityFeePerGas(estimate.getMaxPriorityFeePerGas());
            entity.setBaseFee(estimate.getBaseFee());
            entity.setEstimatedGasLimit(estimate.getEstimatedGasLimit());
            entity.setEstimatedUsdCost(estimate.getEstimatedUsdCost());
            entity.setTimestamp(estimate.getTimestamp());
            gasEstimateMapper.insert(entity);

            meterRegistry.counter("gas.estimate.count", "chain", type.getCode(), "priority", priority).increment();

            return estimate;
        });
    }

    private GasEstimateResponse calculateGasEstimate(ChainType chainType, String priorityLevel) {
        long baseFee = getBaseFeeFromNetwork(chainType);
        long priorityFee = switch (priorityLevel) {
            case "SLOW" -> (long) (baseFee * 0.05);
            case "FAST" -> (long) (baseFee * 0.3);
            default -> (long) (baseFee * 0.1);
        };

        long maxFee = baseFee + priorityFee * 2;
        long gasPrice = baseFee + priorityFee;
        long gasLimit = 21000L;

        BigDecimal ethPrice = getEthPrice();
        BigDecimal gasCostEth = new BigInteger(String.valueOf(gasPrice * gasLimit))
                .divide(new BigDecimal("1000000000000000000"));
        BigDecimal usdCost = gasCostEth.multiply(ethPrice);

        GasEstimateResponse response = new GasEstimateResponse();
        response.setChainType(chainType.getCode());
        response.setPriorityLevel(priorityLevel);
        response.setGasPrice("0x" + Long.toHexString(gasPrice));
        response.setMaxFeePerGas("0x" + Long.toHexString(maxFee));
        response.setMaxPriorityFeePerGas("0x" + Long.toHexString(priorityFee));
        response.setBaseFee("0x" + Long.toHexString(baseFee));
        response.setEstimatedGasLimit("0x" + Long.toHexString(gasLimit));
        response.setEstimatedUsdCost(usdCost);
        response.setTimestamp(System.currentTimeMillis());
        response.setHistoricalTrend(getHistoricalTrend(chainType));

        return response;
    }

    private long getBaseFeeFromNetwork(ChainType chainType) {
        Random random = new Random();
        return switch (chainType) {
            case ETH -> 20000000000L + random.nextInt(10000000000);
            case POLYGON -> 30000000000L + random.nextInt(10000000000);
            case BSC -> 5000000000L + random.nextInt(2000000000);
            default -> 10000000000L + random.nextInt(5000000000);
        };
    }

    private BigDecimal getEthPrice() {
        return new BigDecimal("3000.0");
    }

    private Map<String, String> getHistoricalTrend(ChainType chainType) {
        Map<String, String> trend = new HashMap<>();
        trend.put("1h", "-2.3%");
        trend.put("24h", "+5.1%");
        trend.put("7d", "-1.2%");
        return trend;
    }

    public Flux<GasEstimateResponse> estimateAll(String chainType) {
        return Flux.just("SLOW", "STANDARD", "FAST")
                .flatMap(priority -> estimateGas(chainType, priority));
    }

    public Mono<List<GasHistory>> getGasHistory(String chainType, Integer limit) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GasHistory>();
            wrapper.eq(GasHistory::getChainType, chainType);
            wrapper.orderByDesc(GasHistory::getBlockNumber);
            wrapper.last("LIMIT " + (limit != null ? limit : 100));
            return gasHistoryMapper.selectList(wrapper);
        });
    }

    @Scheduled(fixedRate = 300000)
    public void collectGasHistory() {
        for (ChainType chainType : ChainType.values()) {
            try {
                long baseFee = getBaseFeeFromNetwork(chainType);
                GasHistory history = new GasHistory();
                history.setChainType(chainType.getCode());
                history.setBlockNumber(System.currentTimeMillis() / 1000);
                history.setBaseFee("0x" + Long.toHexString(baseFee));
                history.setAvgGasPrice("0x" + Long.toHexString((long) (baseFee * 1.1)));
                history.setGasUsedRatio(new BigDecimal("0.5" + Math.random() * 0.4));
                history.setTimestamp(System.currentTimeMillis());
                gasHistoryMapper.insert(history);
            } catch (Exception e) {
                log.warn("Failed to collect gas history for {}", chainType.getCode(), e);
            }
        }
    }
}
