package com.web3platform.gasestimator.service;

import com.web3platform.chaininteraction.config.ChainInteractionConfig;
import com.web3platform.gasestimator.model.GasEstimateRequest;
import com.web3platform.gasestimator.model.GasEstimateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GasEstimatorService {

    private final GasPriceOracle gasPriceOracle;
    private final NetworkAnalyzer networkAnalyzer;
    private final FeeHistoryCollector feeHistoryCollector;
    private final ChainInteractionConfig chainInteractionConfig;

    private final Map<String, Web3j> web3jMap = new ConcurrentHashMap<>();

    public GasEstimateResponse estimateGas(GasEstimateRequest request) {
        log.info("Estimating gas for chain: {}, txType: {}, speed: {}",
                request.getChainId(), request.getTxType(), request.getSpeed());

        GasEstimateResponse response = new GasEstimateResponse();
        response.setTimestamp(LocalDateTime.now());

        String chainId = request.getChainId();
        String speed = request.getSpeed() != null ? request.getSpeed() : "NORMAL";
        String txType = request.getTxType() != null ? request.getTxType() : "EIP1559";

        Long gasLimit = request.getGasLimit();
        if (gasLimit == null) {
            gasLimit = estimateGasLimit(request);
        }
        response.setGasLimit(gasLimit);

        double confidence = gasPriceOracle.calculateConfidence(chainId, speed);
        response.setConfidence(confidence);

        if ("LEGACY".equalsIgnoreCase(txType)) {
            BigInteger gasPrice = gasPriceOracle.getLegacyGasPrice(chainId, speed);
            response.setGasPrice(gasPrice);
            response.setBaseFee(BigInteger.ZERO);
            response.setPriorityFee(BigInteger.ZERO);
            response.setMaxFeePerGas(BigInteger.ZERO);
            response.setMaxPriorityFeePerGas(BigInteger.ZERO);
            response.setEstimatedCost(calculateCost(gasPrice, gasLimit));
        } else {
            Map<String, BigInteger> eip1559Fees = gasPriceOracle.getEip1559Fees(chainId, speed);
            BigInteger maxFeePerGas = eip1559Fees.get("maxFeePerGas");
            BigInteger maxPriorityFeePerGas = eip1559Fees.get("maxPriorityFeePerGas");
            BigInteger baseFee = eip1559Fees.get("baseFee");

            response.setBaseFee(baseFee);
            response.setPriorityFee(maxPriorityFeePerGas);
            response.setMaxFeePerGas(maxFeePerGas);
            response.setMaxPriorityFeePerGas(maxPriorityFeePerGas);
            response.setGasPrice(maxFeePerGas);
            response.setEstimatedCost(calculateCost(maxFeePerGas, gasLimit));
        }

        log.info("Gas estimation completed for chain {}: cost={}", chainId, response.getEstimatedCost());
        return response;
    }

    public List<GasEstimateResponse> estimateBatch(List<GasEstimateRequest> requests) {
        List<GasEstimateResponse> responses = new ArrayList<>();
        for (GasEstimateRequest request : requests) {
            try {
                responses.add(estimateGas(request));
            } catch (Exception e) {
                log.error("Failed to estimate gas for request: {}", request, e);
                GasEstimateResponse errorResponse = new GasEstimateResponse();
                errorResponse.setTimestamp(LocalDateTime.now());
                errorResponse.setConfidence(0.0);
                errorResponse.setGasLimit(request.getGasLimit() != null ? request.getGasLimit() : 21000L);
                errorResponse.setGasPrice(BigInteger.ZERO);
                errorResponse.setBaseFee(BigInteger.ZERO);
                errorResponse.setPriorityFee(BigInteger.ZERO);
                errorResponse.setMaxFeePerGas(BigInteger.ZERO);
                errorResponse.setMaxPriorityFeePerGas(BigInteger.ZERO);
                errorResponse.setEstimatedCost(BigDecimal.ZERO);
                responses.add(errorResponse);
            }
        }
        return responses;
    }

    public Long estimateGasLimit(GasEstimateRequest request) {
        try {
            Web3j web3j = getWeb3j(request.getChainId());
            if (web3j == null) {
                return 21000L;
            }

            Transaction tx = Transaction.createEthCallTransaction(
                    null,
                    request.getToAddress(),
                    request.getData()
            );

            BigInteger gasLimit = web3j.ethEstimateGas(tx).send().getAmountUsed();
            return gasLimit.longValue();
        } catch (Exception e) {
            log.warn("Failed to estimate gas limit, using default 21000: {}", e.getMessage());
            return 21000L;
        }
    }

    public BigDecimal calculateCost(BigInteger gasPrice, Long gasLimit) {
        BigDecimal gasPriceDecimal = new BigDecimal(gasPrice);
        BigDecimal gasLimitDecimal = new BigDecimal(gasLimit);
        return gasPriceDecimal.multiply(gasLimitDecimal);
    }

    private Web3j getWeb3j(String chainId) {
        Web3j web3j = web3jMap.get(chainId);
        if (web3j == null) {
            for (ChainInteractionConfig.ChainConfig chainConfig : chainInteractionConfig.getChains()) {
                if (chainConfig.getChainId().equals(chainId) && "EVM".equalsIgnoreCase(chainConfig.getChainType())) {
                    web3j = Web3j.build(new HttpService(chainConfig.getRpcUrl()));
                    web3jMap.put(chainId, web3j);
                    break;
                }
            }
        }
        return web3j;
    }
}
