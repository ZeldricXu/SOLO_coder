package com.web3platform.gasestimator.service;

import com.web3platform.chaininteraction.service.ChainClientFactory;
import com.web3platform.gasestimator.model.FeeHistoryPoint;
import com.web3platform.gasestimator.model.NetworkStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkAnalyzer {

    private final ChainClientFactory chainClientFactory;
    private final FeeHistoryCollector feeHistoryCollector;

    public NetworkStatus analyzeNetworkStatus(String chainId) {
        NetworkStatus status = new NetworkStatus();
        status.setChainId(chainId);

        try {
            long latestBlockNumber = chainClientFactory.getClient(chainId).getLatestBlockNumber(chainId);
            var block = chainClientFactory.getClient(chainId).getBlockByNumber(chainId, latestBlockNumber);

            if (block != null && block.getTransactions() != null) {
                status.setPendingTxCount(block.getTransactions().size());
            } else {
                status.setPendingTxCount(0);
            }

            status.setBlockGasLimit(30000000L);
            status.setBlockGasUsed(estimateBlockGasUsed(chainId));
            status.setCongestionLevel(estimateCongestionLevel(chainId));
            status.setBaseFeeTrend(detectBaseFeeTrend(chainId, 10));
        } catch (Exception e) {
            log.error("Failed to analyze network status for chain {}", chainId, e);
            status.setPendingTxCount(0);
            status.setBlockGasUsed(0L);
            status.setBlockGasLimit(30000000L);
            status.setCongestionLevel("MEDIUM");
            status.setBaseFeeTrend("STABLE");
        }

        return status;
    }

    public String detectBaseFeeTrend(String chainId, int recentBlocks) {
        try {
            List<FeeHistoryPoint> history = feeHistoryCollector.collectFeeHistory(chainId, recentBlocks);

            if (history.size() < 3) {
                return "STABLE";
            }

            List<BigInteger> baseFees = history.stream()
                    .map(FeeHistoryPoint::getBaseFee)
                    .toList();

            int size = baseFees.size();
            if (size < 3) {
                return "STABLE";
            }

            BigInteger first = baseFees.get(0);
            BigInteger last = baseFees.get(size - 1);

            BigInteger diff = last.subtract(first);
            double changePercent = new java.math.BigDecimal(diff)
                    .divide(new java.math.BigDecimal(first), 4, java.math.RoundingMode.HALF_UP)
                    .doubleValue() * 100;

            if (changePercent > 5) {
                return "UP";
            } else if (changePercent < -5) {
                return "DOWN";
            } else {
                return "STABLE";
            }
        } catch (Exception e) {
            log.warn("Failed to detect base fee trend for chain {}: {}", chainId, e.getMessage());
            return "STABLE";
        }
    }

    public String estimateCongestionLevel(String chainId) {
        try {
            List<FeeHistoryPoint> history = feeHistoryCollector.collectFeeHistory(chainId, 5);

            if (history.isEmpty()) {
                return "MEDIUM";
            }

            double avgGasUsedRatio = history.stream()
                    .mapToDouble(FeeHistoryPoint::getGasUsedRatio)
                    .average()
                    .orElse(0.5);

            if (avgGasUsedRatio >= 0.9) {
                return "EXTREME";
            } else if (avgGasUsedRatio >= 0.7) {
                return "HIGH";
            } else if (avgGasUsedRatio >= 0.5) {
                return "MEDIUM";
            } else {
                return "LOW";
            }
        } catch (Exception e) {
            log.warn("Failed to estimate congestion level for chain {}: {}", chainId, e.getMessage());
            return "MEDIUM";
        }
    }

    private long estimateBlockGasUsed(String chainId) {
        try {
            List<FeeHistoryPoint> history = feeHistoryCollector.collectFeeHistory(chainId, 5);
            if (history.isEmpty()) {
                return 15000000L;
            }
            double avgRatio = history.stream()
                    .mapToDouble(FeeHistoryPoint::getGasUsedRatio)
                    .average()
                    .orElse(0.5);
            return (long) (30000000L * avgRatio);
        } catch (Exception e) {
            return 15000000L;
        }
    }
}
