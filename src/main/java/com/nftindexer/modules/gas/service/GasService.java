package com.nftindexer.modules.gas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.GasEstimate;
import com.nftindexer.entity.GasHistory;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.GasEstimateMapper;
import com.nftindexer.mapper.GasHistoryMapper;
import com.nftindexer.modules.gas.dto.GasEstimateRequest;
import com.nftindexer.modules.gas.dto.GasHistoryRecordRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasService {

    private final GasEstimateMapper gasEstimateMapper;
    private final GasHistoryMapper gasHistoryMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Sinks.Many<DomainEvent> eventSink;

    @Value("${nftindexer.gas.default-historical-blocks:100}")
    private int defaultHistoricalBlocks;

    @Value("${nftindexer.gas.default-gas-limit:21000}")
    private BigInteger defaultGasLimit;

    @Value("${nftindexer.gas.cache-ttl-seconds:30}")
    private int cacheTtlSeconds;

    @Cacheable(value = "gasEstimate", key = "#request.chainId + '_' + #request.priorityLevel",
            unless = "#result == null")
    public Mono<GasEstimate> estimateGas(GasEstimateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String chainId = request.getChainId();
                    int historicalBlocks = request.getHistoricalBlocks() != null ?
                            request.getHistoricalBlocks() : defaultHistoricalBlocks;

                    List<GasHistory> history = getRecentGasHistory(chainId, historicalBlocks);
                    if (history.isEmpty()) {
                        return getDefaultEstimate(chainId, request);
                    }

                    BigInteger baseFee = calculateBaseFee(history);
                    BigInteger gasLimit = request.getGasLimit() != null ?
                            request.getGasLimit() : estimateGasLimit(request);

                    Map<String, BigInteger> priorityFees = calculatePriorityFees(history,
                            request.getPriorityLevel() != null ? request.getPriorityLevel() : 2);

                    BigInteger priorityFeeLow = priorityFees.get("low");
                    BigInteger priorityFeeMedium = priorityFees.get("medium");
                    BigInteger priorityFeeHigh = priorityFees.get("high");

                    BigInteger maxFeeLow = baseFee.multiply(BigInteger.valueOf(2)).add(priorityFeeLow);
                    BigInteger maxFeeMedium = baseFee.multiply(BigInteger.valueOf(2)).add(priorityFeeMedium);
                    BigInteger maxFeeHigh = baseFee.multiply(BigInteger.valueOf(2)).add(priorityFeeHigh);

                    BigInteger estimatedCostLow = maxFeeLow.multiply(gasLimit);
                    BigInteger estimatedCostMedium = maxFeeMedium.multiply(gasLimit);
                    BigInteger estimatedCostHigh = maxFeeHigh.multiply(gasLimit);

                    String estimateId = "gse-" + UUID.randomUUID().toString().substring(0, 8);
                    GasEstimate estimate = new GasEstimate();
                    estimate.setEstimateId(estimateId);
                    estimate.setChainId(chainId);
                    estimate.setBaseFee(baseFee);
                    estimate.setPriorityFeeLow(priorityFeeLow);
                    estimate.setPriorityFeeMedium(priorityFeeMedium);
                    estimate.setPriorityFeeHigh(priorityFeeHigh);
                    estimate.setMaxFeeLow(maxFeeLow);
                    estimate.setMaxFeeMedium(maxFeeMedium);
                    estimate.setMaxFeeHigh(maxFeeHigh);
                    estimate.setGasLimit(gasLimit);
                    estimate.setEstimatedCostLow(estimatedCostLow);
                    estimate.setEstimatedCostMedium(estimatedCostMedium);
                    estimate.setEstimatedCostHigh(estimatedCostHigh);
                    estimate.setBlockNumber(history.get(0).getBlockNumber());
                    estimate.setTimestamp(LocalDateTime.now());
                    estimate.setHistoricalData(Map.of(
                            "blockCount", history.size(),
                            "baseFeeTrend", calculateTrend(history),
                            "volatility", calculateVolatility(history)
                    ));
                    estimate.setNetworkStatus(calculateNetworkStatus(history));

                    gasEstimateMapper.insert(estimate);

                    cacheGasEstimate(chainId, estimate);
                    emitEvent("gas.estimated", estimateId, "gas_estimate", estimate, traceId);

                    log.info("Gas estimate created for chain {}: baseFee={}, mediumMaxFee={}",
                            chainId, baseFee, maxFeeMedium);

                    return estimate;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<GasHistory> recordGasHistory(GasHistoryRecordRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<GasHistory> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(GasHistory::getChainId, request.getChainId());
                    existingWrapper.eq(GasHistory::getBlockNumber, request.getBlockNumber());
                    if (gasHistoryMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("该区块Gas历史已存在");
                    }

                    String historyId = "gsh-" + UUID.randomUUID().toString().substring(0, 8);
                    GasHistory history = new GasHistory();
                    history.setHistoryId(historyId);
                    history.setChainId(request.getChainId());
                    history.setBlockNumber(request.getBlockNumber());
                    history.setBaseFee(request.getBaseFee());
                    history.setGasUsed(request.getGasUsed());
                    history.setGasLimit(request.getGasLimit());
                    history.setGasUtilization(request.getGasUtilization());
                    history.setPriorityFeeMin(request.getPriorityFeeMin());
                    history.setPriorityFeeAvg(request.getPriorityFeeAvg());
                    history.setPriorityFeeMax(request.getPriorityFeeMax());
                    history.setBlockTime(request.getBlockTime() != null ?
                            request.getBlockTime() : LocalDateTime.now());

                    gasHistoryMapper.insert(history);

                    updateGasCache(request.getChainId(), history);
                    emitEvent("gas.history.recorded", historyId, "gas_history", history, traceId);

                    log.debug("Recorded gas history for block {} on chain {}",
                            request.getBlockNumber(), request.getChainId());

                    return history;
                }));
    }

    @Cacheable(value = "gasEstimateLatest", key = "#chainId", unless = "#result == null")
    public Mono<GasEstimate> getLatestGasEstimate(String chainId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GasEstimate> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GasEstimate::getChainId, chainId);
            wrapper.orderByDesc(GasEstimate::getBlockNumber);
            wrapper.last("LIMIT 1");
            GasEstimate estimate = gasEstimateMapper.selectOne(wrapper);

            if (estimate == null) {
                throw BusinessException.notFound("无可用的Gas预估数据: " + chainId);
            }

            return estimate;
        });
    }

    public Mono<Map<String, GasEstimate>> getLatestGasEstimates(List<String> chainIds) {
        return Mono.fromCallable(() -> {
            Map<String, GasEstimate> estimates = new HashMap<>();
            for (String chainId : chainIds) {
                try {
                    LambdaQueryWrapper<GasEstimate> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(GasEstimate::getChainId, chainId);
                    wrapper.orderByDesc(GasEstimate::getBlockNumber);
                    wrapper.last("LIMIT 1");
                    GasEstimate estimate = gasEstimateMapper.selectOne(wrapper);
                    if (estimate != null) {
                        estimates.put(chainId, estimate);
                    }
                } catch (Exception e) {
                    log.warn("Failed to get gas estimate for chain {}", chainId, e);
                }
            }
            return estimates;
        });
    }

    public Mono<Page<GasHistory>> getGasHistory(String chainId, Integer startBlock,
                                                 Integer endBlock, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<GasHistory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(GasHistory::getChainId, chainId);
            if (startBlock != null) {
                wrapper.ge(GasHistory::getBlockNumber, startBlock);
            }
            if (endBlock != null) {
                wrapper.le(GasHistory::getBlockNumber, endBlock);
            }
            wrapper.orderByDesc(GasHistory::getBlockNumber);
            return gasHistoryMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<Map<String, Object>> getGasStatistics(String chainId, int blocks) {
        return Mono.fromCallable(() -> {
            List<GasHistory> history = getRecentGasHistory(chainId, blocks);

            Map<String, Object> stats = new HashMap<>();
            if (history.isEmpty()) {
                return stats;
            }

            BigInteger minBaseFee = history.stream()
                    .map(GasHistory::getBaseFee)
                    .min(BigInteger::compareTo)
                    .orElse(BigInteger.ZERO);
            BigInteger maxBaseFee = history.stream()
                    .map(GasHistory::getBaseFee)
                    .max(BigInteger::compareTo)
                    .orElse(BigInteger.ZERO);
            BigInteger avgBaseFee = calculateAverage(history.stream()
                    .map(GasHistory::getBaseFee)
                    .toList());

            BigInteger avgPriorityFee = calculateAverage(history.stream()
                    .map(GasHistory::getPriorityFeeAvg)
                    .filter(fee -> fee != null)
                    .toList());

            Double avgUtilization = history.stream()
                    .map(GasHistory::getGasUtilization)
                    .filter(u -> u != null)
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            stats.put("chainId", chainId);
            stats.put("blockCount", history.size());
            stats.put("minBaseFee", minBaseFee);
            stats.put("maxBaseFee", maxBaseFee);
            stats.put("avgBaseFee", avgBaseFee);
            stats.put("avgPriorityFee", avgPriorityFee);
            stats.put("avgUtilization", avgUtilization);
            stats.put("trend", calculateTrend(history));
            stats.put("volatility", calculateVolatility(history));

            return stats;
        });
    }

    public Mono<Map<String, BigInteger>> suggestGasPrice(String chainId, int priorityLevel) {
        return Mono.fromCallable(() -> {
            List<GasHistory> history = getRecentGasHistory(chainId, 50);
            if (history.isEmpty()) {
                return getDefaultGasPrice(chainId);
            }

            BigInteger baseFee = calculateBaseFee(history);
            Map<String, BigInteger> priorityFees = calculatePriorityFees(history, priorityLevel);

            Map<String, BigInteger> result = new HashMap<>();
            result.put("baseFee", baseFee);
            result.put("priorityFee", priorityFees.get(
                    switch (priorityLevel) {
                        case 1 -> "low";
                        case 3 -> "high";
                        default -> "medium";
                    }
            ));
            result.put("maxFee", baseFee.multiply(BigInteger.valueOf(2))
                    .add(result.get("priorityFee")));

            return result;
        });
    }

    @Scheduled(fixedRateString = "${nftindexer.gas.cleanup-interval-ms:3600000}")
    @CacheEvict(value = {"gasEstimate", "gasEstimateLatest"}, allEntries = true)
    public void cleanupOldGasData() {
        log.info("Starting gas data cleanup...");
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            LambdaQueryWrapper<GasHistory> historyWrapper = new LambdaQueryWrapper<>();
            historyWrapper.lt(GasHistory::getBlockTime, cutoff);
            int deletedHistory = gasHistoryMapper.delete(historyWrapper);

            LambdaQueryWrapper<GasEstimate> estimateWrapper = new LambdaQueryWrapper<>();
            estimateWrapper.lt(GasEstimate::getTimestamp, cutoff.minusDays(1));
            int deletedEstimates = gasEstimateMapper.delete(estimateWrapper);

            log.info("Gas data cleanup completed: deleted {} history records, {} estimates",
                    deletedHistory, deletedEstimates);
        } catch (Exception e) {
            log.error("Gas data cleanup failed", e);
        }
    }

    private List<GasHistory> getRecentGasHistory(String chainId, int blocks) {
        LambdaQueryWrapper<GasHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasHistory::getChainId, chainId);
        wrapper.orderByDesc(GasHistory::getBlockNumber);
        wrapper.last("LIMIT " + blocks);
        return gasHistoryMapper.selectList(wrapper);
    }

    private BigInteger calculateBaseFee(List<GasHistory> history) {
        if (history.isEmpty()) {
            return BigInteger.valueOf(1_000_000_000L);
        }
        List<BigInteger> recentBaseFees = history.stream()
                .limit(10)
                .map(GasHistory::getBaseFee)
                .sorted(Comparator.reverseOrder())
                .toList();

        int count = Math.min(5, recentBaseFees.size());
        return calculateAverage(recentBaseFees.subList(0, count));
    }

    private Map<String, BigInteger> calculatePriorityFees(List<GasHistory> history, int priorityLevel) {
        List<BigInteger> priorityFees = history.stream()
                .map(GasHistory::getPriorityFeeAvg)
                .filter(fee -> fee != null && fee.compareTo(BigInteger.ZERO) > 0)
                .sorted()
                .toList();

        BigInteger low, medium, high;
        if (priorityFees.isEmpty()) {
            low = BigInteger.valueOf(1_000_000_000L);
            medium = BigInteger.valueOf(2_000_000_000L);
            high = BigInteger.valueOf(3_000_000_000L);
        } else {
            int size = priorityFees.size();
            low = priorityFees.get((int) (size * 0.25));
            medium = priorityFees.get((int) (size * 0.5));
            high = priorityFees.get((int) (size * 0.9));

            BigInteger minLow = BigInteger.valueOf(100_000_000L);
            if (low.compareTo(minLow) < 0) low = minLow;
            if (medium.compareTo(low.multiply(BigInteger.valueOf(2))) < 0) {
                medium = low.multiply(BigInteger.valueOf(2));
            }
            if (high.compareTo(medium.multiply(BigInteger.valueOf(2))) < 0) {
                high = medium.multiply(BigInteger.valueOf(2));
            }
        }

        double multiplier = switch (priorityLevel) {
            case 1 -> 0.8;
            case 3 -> 1.5;
            default -> 1.0;
        };

        Map<String, BigInteger> result = new HashMap<>();
        result.put("low", new BigDecimal(low).multiply(BigDecimal.valueOf(multiplier))
                .toBigInteger());
        result.put("medium", new BigDecimal(medium).multiply(BigDecimal.valueOf(multiplier))
                .toBigInteger());
        result.put("high", new BigDecimal(high).multiply(BigDecimal.valueOf(multiplier))
                .toBigInteger());

        return result;
    }

    private BigInteger estimateGasLimit(GasEstimateRequest request) {
        String txType = request.getTransactionType() != null ?
                request.getTransactionType().toLowerCase() : "transfer";
        return switch (txType) {
            case "nft_mint", "nft_transfer", "erc721" -> BigInteger.valueOf(100_000L);
            case "erc20_transfer", "token_transfer" -> BigInteger.valueOf(65_000L);
            case "contract_deployment" -> BigInteger.valueOf(2_000_000L);
            case "swap" -> BigInteger.valueOf(150_000L);
            default -> defaultGasLimit;
        };
    }

    private BigInteger calculateAverage(List<BigInteger> values) {
        if (values.isEmpty()) return BigInteger.ZERO;
        BigInteger sum = values.stream().reduce(BigInteger.ZERO, BigInteger::add);
        return sum.divide(BigInteger.valueOf(values.size()));
    }

    private String calculateTrend(List<GasHistory> history) {
        if (history.size() < 2) return "stable";

        List<GasHistory> recent = history.stream().limit(10).toList();
        List<GasHistory> older = history.stream().skip(10).limit(10).toList();

        if (older.isEmpty()) return "stable";

        BigInteger recentAvg = calculateAverage(recent.stream()
                .map(GasHistory::getBaseFee).toList());
        BigInteger olderAvg = calculateAverage(older.stream()
                .map(GasHistory::getBaseFee).toList());

        if (olderAvg.equals(BigInteger.ZERO)) return "stable";

        double change = new BigDecimal(recentAvg.subtract(olderAvg))
                .divide(new BigDecimal(olderAvg), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        if (change > 10) return "rising_rapidly";
        if (change > 3) return "rising";
        if (change < -10) return "falling_rapidly";
        if (change < -3) return "falling";
        return "stable";
    }

    private double calculateVolatility(List<GasHistory> history) {
        if (history.size() < 5) return 0.0;

        List<BigInteger> baseFees = history.stream()
                .map(GasHistory::getBaseFee)
                .toList();

        BigInteger avg = calculateAverage(baseFees);
        if (avg.equals(BigInteger.ZERO)) return 0.0;

        double sumSquaredDiff = baseFees.stream()
                .mapToDouble(fee -> Math.pow(
                        new BigDecimal(fee.subtract(avg))
                                .divide(new BigDecimal(avg), 10, RoundingMode.HALF_UP)
                                .doubleValue(), 2))
                .sum();

        return Math.sqrt(sumSquaredDiff / baseFees.size()) * 100;
    }

    private Map<String, Object> calculateNetworkStatus(List<GasHistory> history) {
        Map<String, Object> status = new HashMap<>();

        double avgUtilization = history.stream()
                .map(GasHistory::getGasUtilization)
                .filter(u -> u != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        String congestion;
        if (avgUtilization > 0.9) congestion = "extreme";
        else if (avgUtilization > 0.8) congestion = "high";
        else if (avgUtilization > 0.6) congestion = "moderate";
        else if (avgUtilization > 0.4) congestion = "low";
        else congestion = "idle";

        status.put("avgUtilization", avgUtilization);
        status.put("congestion", congestion);
        status.put("recentBlockCount", history.size());
        status.put("trend", calculateTrend(history));

        return status;
    }

    private GasEstimate getDefaultEstimate(String chainId, GasEstimateRequest request) {
        String estimateId = "gse-" + UUID.randomUUID().toString().substring(0, 8);
        GasEstimate estimate = new GasEstimate();
        estimate.setEstimateId(estimateId);
        estimate.setChainId(chainId);
        estimate.setBaseFee(BigInteger.valueOf(1_000_000_000L));
        estimate.setPriorityFeeLow(BigInteger.valueOf(1_000_000_000L));
        estimate.setPriorityFeeMedium(BigInteger.valueOf(2_000_000_000L));
        estimate.setPriorityFeeHigh(BigInteger.valueOf(5_000_000_000L));
        estimate.setMaxFeeLow(BigInteger.valueOf(3_000_000_000L));
        estimate.setMaxFeeMedium(BigInteger.valueOf(4_000_000_000L));
        estimate.setMaxFeeHigh(BigInteger.valueOf(7_000_000_000L));
        estimate.setGasLimit(request.getGasLimit() != null ?
                request.getGasLimit() : defaultGasLimit);
        estimate.setBlockNumber(0);
        estimate.setTimestamp(LocalDateTime.now());
        estimate.setHistoricalData(Map.of("note", "使用默认值，无历史数据"));
        return estimate;
    }

    private Map<String, BigInteger> getDefaultGasPrice(String chainId) {
        Map<String, BigInteger> result = new HashMap<>();
        result.put("baseFee", BigInteger.valueOf(1_000_000_000L));
        result.put("priorityFee", BigInteger.valueOf(2_000_000_000L));
        result.put("maxFee", BigInteger.valueOf(4_000_000_000L));
        return result;
    }

    private void cacheGasEstimate(String chainId, GasEstimate estimate) {
        try {
            String cacheKey = "gas:estimate:" + chainId;
            redisTemplate.opsForValue()
                    .set(cacheKey, estimate, Duration.ofSeconds(cacheTtlSeconds))
                    .block();
        } catch (Exception e) {
            log.warn("Failed to cache gas estimate", e);
        }
    }

    private void updateGasCache(String chainId, GasHistory history) {
        try {
            String cacheKey = "gas:history:" + chainId + ":" + history.getBlockNumber();
            redisTemplate.opsForValue()
                    .set(cacheKey, history, Duration.ofHours(24))
                    .block();
        } catch (Exception e) {
            log.warn("Failed to update gas cache", e);
        }
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }
}
