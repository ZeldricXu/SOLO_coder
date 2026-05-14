package com.paycenter.service;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.enums.FailoverStrategyType;
import com.paycenter.enums.PeriodType;

import java.util.List;
import java.util.Optional;

public interface MerchantConfigService {
    MerchantConfig getOrCreateDefaultConfig(String merchantId);
    MerchantConfig updateFailoverStrategy(String merchantId,
                                           FailoverStrategyType strategyType,
                                           Integer threshold,
                                           Integer retryInterval,
                                           Integer maxRetryCount);
    MerchantConfig updateSettlementConfig(String merchantId,
                                           PeriodType periodType,
                                           String periodConfig,
                                           java.math.BigDecimal minAmount,
                                           Boolean autoEnabled);
    Optional<MerchantConfig> getConfigByMerchantId(String merchantId);
    long getTotalMerchantCount();
    List<String> getAllMerchantIds();
    boolean shouldFailover(String merchantId,
                           com.paycenter.enums.ChannelType channelType,
                           int currentFailures);
}
