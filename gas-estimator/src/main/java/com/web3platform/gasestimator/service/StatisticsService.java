package com.web3platform.gasestimator.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.web3platform.gasestimator.model.HistoricalGasData;
import com.web3platform.persistence.mapper.GasEstimateMapper;
import com.web3platform.persistence.model.entity.GasEstimate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final GasEstimateMapper gasEstimateMapper;
    private final FeeHistoryCollector feeHistoryCollector;

    public BigInteger getAverageGasPrice(String chainId, int durationMinutes) {
        try {
            LocalDateTime fromTime = LocalDateTime.now().minusMinutes(durationMinutes);
            QueryWrapper<GasEstimate> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_id", chainId)
                    .ge("recorded_at", fromTime)
                    .orderByDesc("recorded_at");

            List<GasEstimate> estimates = gasEstimateMapper.selectList(wrapper);

            if (estimates.isEmpty()) {
                return getFallbackAverageGasPrice(chainId);
            }

            BigDecimal sum = BigDecimal.ZERO;
            for (GasEstimate estimate : estimates) {
                if (estimate.getGasPrice() != null) {
                    sum = sum.add(estimate.getGasPrice());
                }
            }

            if (sum.compareTo(BigDecimal.ZERO) == 0) {
                return getFallbackAverageGasPrice(chainId);
            }

            BigDecimal avg = sum.divide(new BigDecimal(estimates.size()), java.math.RoundingMode.HALF_UP);
            return avg.toBigInteger();
        } catch (Exception e) {
            log.warn("Failed to get average gas price from DB, using fallback: {}", e.getMessage());
            return getFallbackAverageGasPrice(chainId);
        }
    }

    public List<HistoricalGasData> getGasPriceTrend(String chainId, int durationMinutes) {
        try {
            LocalDateTime fromTime = LocalDateTime.now().minusMinutes(durationMinutes);
            QueryWrapper<GasEstimate> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_id", chainId)
                    .ge("recorded_at", fromTime)
                    .orderByAsc("recorded_at");

            List<GasEstimate> estimates = gasEstimateMapper.selectList(wrapper);

            if (estimates.isEmpty()) {
                return getFallbackTrendData(chainId, durationMinutes);
            }

            List<HistoricalGasData> trend = new ArrayList<>();
            for (GasEstimate estimate : estimates) {
                HistoricalGasData data = new HistoricalGasData();
                data.setChainId(chainId);
                data.setBlockNumber(estimate.getBlockNumber());
                data.setBaseFee(estimate.getBaseFee() != null ? estimate.getBaseFee().toBigInteger() : BigInteger.ZERO);
                data.setAvgGasPrice(estimate.getGasPrice() != null ? estimate.getGasPrice().toBigInteger() : BigInteger.ZERO);
                data.setMinGasPrice(estimate.getGasPrice() != null ? estimate.getGasPrice().toBigInteger() : BigInteger.ZERO);
                data.setMaxGasPrice(estimate.getGasPrice() != null ? estimate.getGasPrice().toBigInteger() : BigInteger.ZERO);
                data.setTimestamp(estimate.getRecordedAt() != null ?
                        java.sql.Timestamp.valueOf(estimate.getRecordedAt()).getTime() : System.currentTimeMillis());
                data.setTxCount(0);
                trend.add(data);
            }

            return trend;
        } catch (Exception e) {
            log.warn("Failed to get gas price trend from DB, using fallback: {}", e.getMessage());
            return getFallbackTrendData(chainId, durationMinutes);
        }
    }

    public Map<String, BigInteger> getMinMaxGasPrice(String chainId, int durationMinutes) {
        try {
            LocalDateTime fromTime = LocalDateTime.now().minusMinutes(durationMinutes);
            QueryWrapper<GasEstimate> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_id", chainId)
                    .ge("recorded_at", fromTime);

            List<GasEstimate> estimates = gasEstimateMapper.selectList(wrapper);

            if (estimates.isEmpty()) {
                return getFallbackMinMax(chainId);
            }

            BigInteger minGasPrice = null;
            BigInteger maxGasPrice = null;

            for (GasEstimate estimate : estimates) {
                if (estimate.getGasPrice() != null) {
                    BigInteger price = estimate.getGasPrice().toBigInteger();
                    if (minGasPrice == null || price.compareTo(minGasPrice) < 0) {
                        minGasPrice = price;
                    }
                    if (maxGasPrice == null || price.compareTo(maxGasPrice) > 0) {
                        maxGasPrice = price;
                    }
                }
            }

            if (minGasPrice == null) {
                return getFallbackMinMax(chainId);
            }

            Map<String, BigInteger> result = new HashMap<>();
            result.put("min", minGasPrice);
            result.put("max", maxGasPrice);
            return result;
        } catch (Exception e) {
            log.warn("Failed to get min/max gas price from DB, using fallback: {}", e.getMessage());
            return getFallbackMinMax(chainId);
        }
    }

    private BigInteger getFallbackAverageGasPrice(String chainId) {
        try {
            var history = feeHistoryCollector.collectFeeHistory(chainId, 10);
            if (!history.isEmpty()) {
                BigInteger sum = BigInteger.ZERO;
                int count = 0;
                for (var point : history) {
                    if (point.getBaseFee() != null && point.getReward() != null && !point.getReward().isEmpty()) {
                        sum = sum.add(point.getBaseFee()).add(point.getReward().get(0));
                        count++;
                    }
                }
                if (count > 0) {
                    return sum.divide(BigInteger.valueOf(count));
                }
            }
        } catch (Exception ignored) {
        }
        return new BigInteger("2000000000");
    }

    private List<HistoricalGasData> getFallbackTrendData(String chainId, int durationMinutes) {
        List<HistoricalGasData> trend = new ArrayList<>();
        try {
            var history = feeHistoryCollector.collectFeeHistory(chainId, Math.min(durationMinutes / 5, 20));
            long now = System.currentTimeMillis();
            for (int i = 0; i < history.size(); i++) {
                var point = history.get(i);
                HistoricalGasData data = new HistoricalGasData();
                data.setChainId(chainId);
                data.setBlockNumber(point.getBlockNumber());
                data.setBaseFee(point.getBaseFee() != null ? point.getBaseFee() : BigInteger.ZERO);
                BigInteger avgPrice = point.getBaseFee() != null ? point.getBaseFee() : BigInteger.ZERO;
                if (point.getReward() != null && !point.getReward().isEmpty()) {
                    avgPrice = avgPrice.add(point.getReward().get(0));
                }
                data.setAvgGasPrice(avgPrice);
                data.setMinGasPrice(avgPrice);
                data.setMaxGasPrice(avgPrice);
                data.setTimestamp(now - (history.size() - i) * 300000L);
                data.setTxCount(0);
                trend.add(data);
            }
        } catch (Exception e) {
            log.warn("Failed to get fallback trend data: {}", e.getMessage());
        }
        return trend;
    }

    private Map<String, BigInteger> getFallbackMinMax(String chainId) {
        Map<String, BigInteger> result = new HashMap<>();
        try {
            var history = feeHistoryCollector.collectFeeHistory(chainId, 20);
            if (!history.isEmpty()) {
                BigInteger min = null;
                BigInteger max = null;
                for (var point : history) {
                    if (point.getBaseFee() != null) {
                        BigInteger price = point.getBaseFee();
                        if (point.getReward() != null && !point.getReward().isEmpty()) {
                            price = price.add(point.getReward().get(0));
                        }
                        if (min == null || price.compareTo(min) < 0) {
                            min = price;
                        }
                        if (max == null || price.compareTo(max) > 0) {
                            max = price;
                        }
                    }
                }
                if (min != null && max != null) {
                    result.put("min", min);
                    result.put("max", max);
                    return result;
                }
            }
        } catch (Exception ignored) {
        }
        result.put("min", new BigInteger("1000000000"));
        result.put("max", new BigInteger("3000000000"));
        return result;
    }
}
