package com.contraudit.gas.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.gas.entity.GasEstimation;
import com.contraudit.gas.entity.GasFeeHistory;
import com.contraudit.gas.entity.GasPriceOracle;
import com.contraudit.gas.mapper.GasEstimationMapper;
import com.contraudit.gas.mapper.GasFeeHistoryMapper;
import com.contraudit.gas.mapper.GasPriceOracleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasService {

    private final GasFeeHistoryMapper feeHistoryMapper;
    private final GasEstimationMapper estimationMapper;
    private final GasPriceOracleMapper priceOracleMapper;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private static final BigDecimal GWEI_TO_ETH = new BigDecimal("1000000000");

    @Transactional(rollbackFor = Exception.class)
    public GasEstimation estimateGas(String chainType, String networkId, String txType,
                                   BigDecimal estimatedGasLimit,
                                   BigDecimal customBaseFee, Integer historyHours) {
        if (estimatedGasLimit == null) {
            estimatedGasLimit = new BigDecimal("21000");
        }
        if (historyHours == null) {
            historyHours = 24;
        }

        GasEstimation estimation = new GasEstimation();
        String estimationId = "gas_est_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);
        estimation.setEstimationId(estimationId);
        estimation.setChainType(chainType);
        estimation.setNetworkId(networkId);
        estimation.setTxType(txType);
        estimation.setEstimatedGasLimit(estimatedGasLimit);

        List<GasPriceOracle> oracles = getActiveOracles(chainType, networkId);

        BigDecimal oracleBaseFee = BigDecimal.ZERO;
        BigDecimal oraclePriorityFee = BigDecimal.ZERO;

        if (!oracles.isEmpty()) {
            for (GasPriceOracle oracle : oracles) {
                if (oracle.getBaseFee() != null && oracle.getBaseFee().compareTo(BigDecimal.ZERO) > 0) {
                    oracleBaseFee = oracleBaseFee.add(oracle.getBaseFee());
                }
                if (oracle.getPriorityFee() != null && oracle.getPriorityFee().compareTo(BigDecimal.ZERO) > 0) {
                    oraclePriorityFee = oraclePriorityFee.add(oracle.getPriorityFee());
                }
            }
            oracleBaseFee = oracleBaseFee.divide(new BigDecimal(oracles.size()), 9, RoundingMode.HALF_UP);
            oraclePriorityFee = oraclePriorityFee.divide(new BigDecimal(oracles.size()), 9, RoundingMode.HALF_UP);
        }

        LocalDateTime fromTime = LocalDateTime.now().minusHours(historyHours);
        List<Map<String, Object>> historyStats = feeHistoryMapper.getAverageGasStats(chainType, fromTime);

        BigDecimal historicalAvgPrice = BigDecimal.ZERO;
        BigDecimal historicalAvgPriorityFee = BigDecimal.ZERO;

        if (!historyStats.isEmpty()) {
            for (Map<String, Object> stat : historyStats) {
                if (txType != null && txType.equals(stat.get("tx_type"))) {
                    historicalAvgPrice = new BigDecimal(stat.get("avg_price").toString());
                    if (stat.get("avg_priority_fee") != null) {
                        historicalAvgPriorityFee = new BigDecimal(stat.get("avg_priority_fee").toString());
                    }
                    break;
                }
            }
            if (historicalAvgPrice.compareTo(BigDecimal.ZERO) == 0) {
                historicalAvgPrice = new BigDecimal(historyStats.get(0).get("avg_price").toString());
            }
        }

        BigDecimal baseFee = customBaseFee != null ? customBaseFee :
                (oracleBaseFee.compareTo(BigDecimal.ZERO) > 0 ? oracleBaseFee :
                        (historicalAvgPrice.compareTo(BigDecimal.ZERO) > 0 ?
                                historicalAvgPrice : new BigDecimal("30"));

        BigDecimal basePriorityFee = oraclePriorityFee.compareTo(BigDecimal.ZERO) > 0 ? oraclePriorityFee :
                (historicalAvgPriorityFee.compareTo(BigDecimal.ZERO) > 0 ?
                        historicalAvgPriorityFee : new BigDecimal("2"));

        estimation.setBaseFee(baseFee);

        estimation.setSlowGasPrice(baseFee.multiply(new BigDecimal("0.8")));
        estimation.setStandardGasPrice(baseFee);
        estimation.setFastGasPrice(baseFee.multiply(new BigDecimal("1.2")));

        estimation.setSlowPriorityFee(basePriorityFee.multiply(new BigDecimal("0.8")));
        estimation.setStandardPriorityFee(basePriorityFee);
        estimation.setFastPriorityFee(basePriorityFee.multiply(new BigDecimal("1.3")));

        estimation.setSlowEstimatedCost(
                estimatedGasLimit.multiply(
                        estimation.getSlowGasPrice().add(estimation.getSlowPriorityFee()))
                        .divide(GWEI_TO_ETH, 9, RoundingMode.HALF_UP));
        estimation.setStandardEstimatedCost(
                estimatedGasLimit.multiply(
                        estimation.getStandardGasPrice().add(estimation.getStandardPriorityFee()))
                        .divide(GWEI_TO_ETH, 9, RoundingMode.HALF_UP));
        estimation.setFastEstimatedCost(
                estimatedGasLimit.multiply(
                        estimation.getFastGasPrice().add(estimation.getFastPriorityFee()))
                        .divide(GWEI_TO_ETH, 9, RoundingMode.HALF_UP));

        estimation.setConfidenceLevel(calculateConfidenceLevel(oracles.size(), historyStats.size()));
        estimation.setSampleSize(historyStats.size());
        estimation.setPredictionModel("HYBRID_ORACLE_HISTORY");

        LambdaQueryWrapper<GasFeeHistory> recentWrapper = new LambdaQueryWrapper<>();
        recentWrapper.eq(GasFeeHistory::getChainType, chainType)
                .ge(GasFeeHistory::getCreatedAt, fromTime)
                .orderByDesc(GasFeeHistory::getBlockNumber)
                .last("LIMIT 1");
        GasFeeHistory recentFee = feeHistoryMapper.selectOne(recentWrapper);
        if (recentFee != null) {
            estimation.setFromBlock(recentFee.getBlockNumber() - (historyHours * 200));
            estimation.setToBlock(recentFee.getBlockNumber());
        } else {
            estimation.setFromBlock(0L);
            estimation.setToBlock(0L);
        }

        estimation.setEstimatedAt(LocalDateTime.now());
        estimation.setExpiresAt(LocalDateTime.now().plusMinutes(5));

        estimationMapper.insert(estimation);

        log.info("Gas estimation created: {} for chain={}, network={}, txType={}",
                estimationId, chainType, networkId, txType);

        return estimation;
    }

    @Transactional(rollbackFor = Exception.class)
    public GasFeeHistory recordFeeHistory(GasFeeHistory history) {
        if (history.getRecordedAt() == null) {
            history.setRecordedAt(LocalDateTime.now());
        }
        feeHistoryMapper.insert(history);
        log.info("Recorded gas fee history: tx={}, gasPrice={} Gwei",
                history.getTxHash(), history.getGasPrice());
        return history;
    }

    @Transactional(rollbackFor = Exception.class)
    public GasPriceOracle registerOracle(GasPriceOracle oracle) {
        if (oracle.getStatus() == null) {
            oracle.setStatus(1);
        }
        priceOracleMapper.insert(oracle);
        log.info("Registered gas price oracle: {} - {}", oracle.getOracleName(), oracle.getOracleUrl());
        return oracle;
    }

    @Transactional(rollbackFor = Exception.class)
    public GasPriceOracle updateOraclePrice(String oracleId, BigDecimal gasPrice,
                                           BigDecimal priorityFee, BigDecimal baseFee) {
        GasPriceOracle oracle = priceOracleMapper.selectById(oracleId);
        if (oracle == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "oracle not found");
        }
        oracle.setCurrentGasPrice(gasPrice);
        oracle.setPriorityFee(priorityFee);
        oracle.setBaseFee(baseFee);
        oracle.setLastUpdated(LocalDateTime.now());
        priceOracleMapper.updateById(oracle);
        log.info("Updated oracle price for {}: gasPrice={}, baseFee={}",
                oracleId, gasPrice, baseFee);
        return oracle;
    }

    public GasEstimation getEstimation(String estimationId) {
        LambdaQueryWrapper<GasEstimation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasEstimation::getEstimationId, estimationId);
        GasEstimation estimation = estimationMapper.selectOne(wrapper);
        if (estimation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "estimation not found");
        }
        return estimation;
    }

    public List<GasEstimation> listEstimations(String chainType, String networkId, String txType) {
        LambdaQueryWrapper<GasEstimation> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(GasEstimation::getChainType, chainType);
        }
        if (networkId != null) {
            wrapper.eq(GasEstimation::getNetworkId, networkId);
        }
        if (txType != null) {
            wrapper.eq(GasEstimation::getTxType, txType);
        }
        wrapper.orderByDesc(GasEstimation::getEstimatedAt);
        wrapper.last("LIMIT 100");
        return estimationMapper.selectList(wrapper);
    }

    public List<GasFeeHistory> listFeeHistory(String chainType, String txType,
                                              LocalDateTime fromTime, LocalDateTime toTime) {
        LambdaQueryWrapper<GasFeeHistory> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(GasFeeHistory::getChainType, chainType);
        }
        if (txType != null) {
            wrapper.eq(GasFeeHistory::getTxType, txType);
        }
        if (fromTime != null) {
            wrapper.ge(GasFeeHistory::getCreatedAt, fromTime);
        }
        if (toTime != null) {
            wrapper.le(GasFeeHistory::getCreatedAt, toTime);
        }
        wrapper.orderByDesc(GasFeeHistory::getCreatedAt);
        wrapper.last("LIMIT 500");
        return feeHistoryMapper.selectList(wrapper);
    }

    public List<GasPriceOracle> getActiveOracles(String chainType, String networkId) {
        LambdaQueryWrapper<GasPriceOracle> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(GasPriceOracle::getChainType, chainType);
        }
        if (networkId != null) {
            wrapper.eq(GasPriceOracle::getNetworkId, networkId);
        }
        wrapper.eq(GasPriceOracle::getStatus, 1);
        return priceOracleMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteOracle(String oracleId) {
        priceOracleMapper.deleteById(oracleId);
    }

    private int calculateConfidenceLevel(int oracleCount, int historyCount) {
        int score = 50;
        if (oracleCount > 0) {
            score += Math.min(oracleCount * 10, 25);
        }
        if (historyCount > 0) {
            score += Math.min(historyCount / 10, 25);
        }
        return Math.min(score, 100);
    }

    public Map<String, Object> getGasPriceStats(String chainType, String networkId) {
        List<GasPriceOracle> oracles = getActiveOracles(chainType, networkId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("chainType", chainType);
        stats.put("networkId", networkId);
        stats.put("oracleCount", oracles.size());
        stats.put("timestamp", System.currentTimeMillis());

        if (!oracles.isEmpty()) {
            BigDecimal avgGasPrice = oracles.stream()
                    .map(o -> o.getCurrentGasPrice() != null ? o.getCurrentGasPrice() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(new BigDecimal(oracles.size()), 9, RoundingMode.HALF_UP);
            BigDecimal avgBaseFee = oracles.stream()
                    .map(o -> o.getBaseFee() != null ? o.getBaseFee() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(new BigDecimal(oracles.size()), 9, RoundingMode.HALF_UP);
            BigDecimal avgPriorityFee = oracles.stream()
                    .map(o -> o.getPriorityFee() != null ? o.getPriorityFee() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(new BigDecimal(oracles.size()), 9, RoundingMode.HALF_UP);

            stats.put("averageGasPrice", avgGasPrice);
            stats.put("averageBaseFee", avgBaseFee);
            stats.put("averagePriorityFee", avgPriorityFee);

            BigDecimal minGasPrice = oracles.stream()
                    .map(o -> o.getCurrentGasPrice() != null ? o.getCurrentGasPrice() : BigDecimal.ZERO)
                    .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal maxGasPrice = oracles.stream()
                    .map(o -> o.getCurrentGasPrice() != null ? o.getCurrentGasPrice() : BigDecimal.ZERO)
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

            stats.put("minGasPrice", minGasPrice);
            stats.put("maxGasPrice", maxGasPrice);
        }

        return stats;
    }
}
