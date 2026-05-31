package com.solocoder.platform.gas.estimator.application.service;

import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.model.GasHistory;
import com.solocoder.platform.gas.estimator.domain.repository.GasEstimationRepository;
import com.solocoder.platform.gas.estimator.domain.repository.GasHistoryRepository;
import com.solocoder.platform.gas.estimator.domain.service.GasPriceCalculator;
import com.solocoder.platform.gas.estimator.domain.service.RequestValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasEstimationApplicationService {

    private final RequestValidator requestValidator;
    private final GasPriceCalculator gasPriceCalculator;
    private final GasEstimationRepository gasEstimationRepository;
    private final GasHistoryRepository gasHistoryRepository;

    @Transactional(rollbackFor = Exception.class)
    public GasEstimation estimateGas(String chainId, String network, Long timestamp, String signature) {
        requestValidator.validateChainId(chainId);
        requestValidator.validateTimestamp(timestamp);
        requestValidator.validateSignature(signature);

        try {
            List<GasHistory> historyData = gasHistoryRepository.findRecentByChainId(chainId, 100);

            BigDecimal baseFee = gasPriceCalculator.calculateBaseFee(historyData);
            GasEstimation.GasPriceLevel gasPrices = gasPriceCalculator.calculateGasPriceLevels(historyData, baseFee);
            GasEstimation.PriorityFeeLevel priorityFees = gasPriceCalculator.calculatePriorityFeeLevels(historyData);
            GasEstimation.NetworkStatus networkStatus = gasPriceCalculator.calculateNetworkStatus(historyData);

            GasHistory latest = gasHistoryRepository.findLatestByChainId(chainId).orElse(null);
            Long latestBlock = latest != null ? latest.getBlockNumber() : 0L;

            GasEstimation estimation = GasEstimation.builder()
                    .chainId(chainId)
                    .network(network != null ? network : "mainnet")
                    .gasPrices(gasPrices)
                    .baseFee(baseFee)
                    .priorityFees(priorityFees)
                    .networkStatus(networkStatus)
                    .latestBlock(latestBlock)
                    .timestamp(System.currentTimeMillis())
                    .signature(signature)
                    .estimationId(UUID.randomUUID().toString())
                    .createdAt(LocalDateTime.now())
                    .build();

            return gasEstimationRepository.save(estimation);
        } catch (DuplicateKeyException e) {
            log.error("并发冲突: chainId={}, error={}", chainId, e.getMessage());
            throw new RuntimeException("409: 并发冲突，资源ID: " + chainId);
        }
    }

    public GasEstimation getEstimation(String estimationId) {
        return gasEstimationRepository.findByEstimationId(estimationId)
                .orElseThrow(() -> new IllegalArgumentException("预估记录不存在: " + estimationId));
    }

    public List<GasEstimation> getRecentEstimations(String chainId, int limit) {
        return gasEstimationRepository.findLatest(chainId, limit);
    }
}
