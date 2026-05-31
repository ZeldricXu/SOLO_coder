package com.solocoder.platform.gas.estimator.adapter.api;

import com.solocoder.platform.gas.estimator.api.GasEstimationApi;
import com.solocoder.platform.gas.estimator.application.service.GasEstimationApplicationService;
import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class GasEstimationApiImpl implements GasEstimationApi {

    private final GasEstimationApplicationService gasEstimationApplicationService;

    @Override
    public GasPriceEstimate estimateGas(String chainId, String txType, Long gasLimit) {
        GasEstimation estimation = gasEstimationApplicationService.estimateGas(
                chainId, null, System.currentTimeMillis(), "internal_call");
        return toGasPriceEstimate(estimation);
    }

    @Override
    public BigDecimal getRecommendedGasPrice(String chainId, GasPriority priority) {
        GasEstimation estimation = gasEstimationApplicationService.estimateGas(
                chainId, null, System.currentTimeMillis(), "internal_call");

        return switch (priority) {
            case LOW -> estimation.getGasPrices().getLow();
            case MEDIUM -> estimation.getGasPrices().getMedium();
            case HIGH -> estimation.getGasPrices().getHigh();
        };
    }

    @Override
    public BigDecimal getBaseFee(String chainId) {
        GasEstimation estimation = gasEstimationApplicationService.estimateGas(
                chainId, null, System.currentTimeMillis(), "internal_call");
        return estimation.getBaseFee();
    }

    @Override
    public GasPriorityFee getPriorityFeeLevels(String chainId) {
        GasEstimation estimation = gasEstimationApplicationService.estimateGas(
                chainId, null, System.currentTimeMillis(), "internal_call");

        return new GasPriorityFee() {
            @Override
            public BigDecimal getLow() {
                return estimation.getPriorityFees().getLow();
            }

            @Override
            public BigDecimal getMedium() {
                return estimation.getPriorityFees().getMedium();
            }

            @Override
            public BigDecimal getHigh() {
                return estimation.getPriorityFees().getHigh();
            }
        };
    }

    private GasPriceEstimate toGasPriceEstimate(GasEstimation estimation) {
        return new GasPriceEstimate() {
            @Override
            public String getChainId() {
                return estimation.getChainId();
            }

            @Override
            public BigDecimal getGasPriceLow() {
                return estimation.getGasPrices().getLow();
            }

            @Override
            public BigDecimal getGasPriceMedium() {
                return estimation.getGasPrices().getMedium();
            }

            @Override
            public BigDecimal getGasPriceHigh() {
                return estimation.getGasPrices().getHigh();
            }

            @Override
            public BigDecimal getBaseFee() {
                return estimation.getBaseFee();
            }

            @Override
            public Long getLatestBlock() {
                return estimation.getLatestBlock();
            }

            @Override
            public Long getTimestamp() {
                return estimation.getTimestamp();
            }
        };
    }
}
