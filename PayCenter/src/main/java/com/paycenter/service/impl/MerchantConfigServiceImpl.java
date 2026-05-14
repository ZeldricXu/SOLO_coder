package com.paycenter.service.impl;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.ChannelFailoverLog;
import com.paycenter.enums.ChannelType;
import com.paycenter.enums.FailoverStrategyType;
import com.paycenter.enums.PeriodType;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.ChannelFailoverLogRepository;
import com.paycenter.repository.MerchantConfigRepository;
import com.paycenter.service.MerchantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MerchantConfigServiceImpl implements MerchantConfigService {

    private static final Logger logger = LoggerFactory.getLogger(MerchantConfigServiceImpl.class);

    @Autowired
    private MerchantConfigRepository merchantConfigRepository;

    @Autowired
    private ChannelFailoverLogRepository channelFailoverLogRepository;

    @Override
    @Transactional
    @Cacheable(value = "merchantConfigs", key = "#merchantId")
    public MerchantConfig getOrCreateDefaultConfig(String merchantId) {
        return merchantConfigRepository.findByMerchantId(merchantId)
                .orElseGet(() -> {
                    logger.info("为商户 {} 创建默认配置", merchantId);
                    MerchantConfig config = MerchantConfig.builder()
                            .merchantId(merchantId)
                            .failoverStrategyType(FailoverStrategyType.NORMAL)
                            .failoverThreshold(3)
                            .failoverRetryInterval(5000)
                            .failoverMaxRetryCount(3)
                            .settlementPeriodType(PeriodType.DAILY)
                            .minSettlementAmount(BigDecimal.ZERO)
                            .autoSettlementEnabled(true)
                            .build();
                    return merchantConfigRepository.save(config);
                });
    }

    @Override
    @Transactional
    @CacheEvict(value = "merchantConfigs", key = "#merchantId")
    public MerchantConfig updateFailoverStrategy(String merchantId,
                                                  FailoverStrategyType strategyType,
                                                  Integer threshold,
                                                  Integer retryInterval,
                                                  Integer maxRetryCount) {
        MerchantConfig config = getOrCreateDefaultConfig(merchantId);
        
        if (strategyType != null) {
            config.setFailoverStrategyType(strategyType);
        }
        
        switch (strategyType) {
            case QUICK:
                config.setFailoverThreshold(1);
                config.setFailoverRetryInterval(1000);
                config.setFailoverMaxRetryCount(1);
                break;
            case NORMAL:
                config.setFailoverThreshold(3);
                config.setFailoverRetryInterval(5000);
                config.setFailoverMaxRetryCount(3);
                break;
            case DELAYED:
                config.setFailoverThreshold(5);
                config.setFailoverRetryInterval(10000);
                config.setFailoverMaxRetryCount(5);
                break;
            case CUSTOM:
                if (threshold != null) {
                    config.setFailoverThreshold(threshold);
                }
                if (retryInterval != null) {
                    config.setFailoverRetryInterval(retryInterval);
                }
                if (maxRetryCount != null) {
                    config.setFailoverMaxRetryCount(maxRetryCount);
                }
                break;
        }
        
        logger.info("更新商户 {} 故障切换策略: type={}, threshold={}", 
                merchantId, config.getFailoverStrategyType(), config.getFailoverThreshold());
        
        return merchantConfigRepository.save(config);
    }

    @Override
    @Transactional
    @CacheEvict(value = "merchantConfigs", key = "#merchantId")
    public MerchantConfig updateSettlementConfig(String merchantId,
                                                  PeriodType periodType,
                                                  String periodConfig,
                                                  BigDecimal minAmount,
                                                  Boolean autoEnabled) {
        MerchantConfig config = getOrCreateDefaultConfig(merchantId);
        
        if (periodType != null) {
            config.setSettlementPeriodType(periodType);
        }
        if (periodConfig != null) {
            config.setSettlementPeriodConfig(periodConfig);
        }
        if (minAmount != null) {
            config.setMinSettlementAmount(minAmount);
        }
        if (autoEnabled != null) {
            config.setAutoSettlementEnabled(autoEnabled);
        }
        
        logger.info("更新商户 {} 结算配置: periodType={}, minAmount={}, autoEnabled={}",
                merchantId, config.getSettlementPeriodType(), 
                config.getMinSettlementAmount(), config.getAutoSettlementEnabled());
        
        return merchantConfigRepository.save(config);
    }

    @Override
    @Cacheable(value = "merchantConfigs", key = "#merchantId")
    public Optional<MerchantConfig> getConfigByMerchantId(String merchantId) {
        return merchantConfigRepository.findByMerchantId(merchantId);
    }

    @Override
    public long getTotalMerchantCount() {
        Long count = merchantConfigRepository.countActiveMerchants();
        return count != null ? count : 0L;
    }

    @Override
    public List<String> getAllMerchantIds() {
        return merchantConfigRepository.findAll().stream()
                .map(MerchantConfig::getMerchantId)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean shouldFailover(String merchantId, ChannelType channelType, int currentFailures) {
        MerchantConfig config = getOrCreateDefaultConfig(merchantId);
        
        logger.debug("检查故障切换条件: merchantId={}, channelType={}, currentFailures={}, threshold={}",
                merchantId, channelType, currentFailures, config.getFailoverThreshold());
        
        return currentFailures >= config.getFailoverThreshold();
    }

    @Transactional
    public void recordFailover(String merchantId,
                               String transactionId,
                               ChannelType channelType,
                               String primaryChannelId,
                               String backupChannelId,
                               int failureCount,
                               String failureReason) {
        ChannelFailoverLog log = ChannelFailoverLog.builder()
                .merchantId(merchantId)
                .transactionId(transactionId)
                .channelType(channelType)
                .primaryChannelId(primaryChannelId)
                .backupChannelId(backupChannelId)
                .failureCount(failureCount)
                .switched(backupChannelId != null)
                .failureReason(failureReason)
                .build();
        
        channelFailoverLogRepository.save(log);
        logger.warn("记录故障切换: merchantId={}, transactionId={}, channelType={}, switched={}",
                merchantId, transactionId, channelType, log.getSwitched());
    }

    public int getRecentFailureCount(String merchantId, ChannelType channelType, int minutes) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        Long count = channelFailoverLogRepository.countRecentFailures(merchantId, channelType, since);
        return count != null ? count.intValue() : 0;
    }
}
