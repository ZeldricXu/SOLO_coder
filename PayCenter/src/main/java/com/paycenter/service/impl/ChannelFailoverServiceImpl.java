package com.paycenter.service.impl;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.enums.ChannelType;
import com.paycenter.enums.FailoverStrategyType;
import com.paycenter.repository.ChannelFailoverLogRepository;
import com.paycenter.service.ChannelFailoverService;
import com.paycenter.service.MerchantConfigService;
import com.paycenter.service.PaymentChannelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChannelFailoverServiceImpl implements ChannelFailoverService {

    private static final Logger logger = LoggerFactory.getLogger(ChannelFailoverServiceImpl.class);

    @Autowired
    private PaymentChannelService paymentChannelService;

    @Autowired
    private MerchantConfigService merchantConfigService;

    @Autowired
    private ChannelFailoverLogRepository channelFailoverLogRepository;

    @Autowired
    private MerchantConfigServiceImpl merchantConfigServiceImpl;

    private final Map<String, Map<ChannelType, Integer>> channelFailureCounts = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentChannel> getPrimaryChannel(String merchantId, ChannelType channelType) {
        List<PaymentChannel> availableChannels = getAvailableChannels(channelType);
        
        if (availableChannels.isEmpty()) {
            return Optional.empty();
        }
        
        PaymentChannel primaryChannel = findActivePrimaryChannel(merchantId, channelType, availableChannels);
        return Optional.ofNullable(primaryChannel);
    }

    @Override
    public Optional<PaymentChannel> getBackupChannel(String merchantId, ChannelType channelType) {
        List<PaymentChannel> availableChannels = getAvailableChannels(channelType);
        
        if (availableChannels.size() <= 1) {
            return Optional.empty();
        }
        
        MerchantConfig config = merchantConfigService.getOrCreateDefaultConfig(merchantId);
        int failureCount = getFailureCount(merchantId, channelType);
        
        if (merchantConfigService.shouldFailover(merchantId, channelType, failureCount)) {
            PaymentChannel backupChannel = findBackupChannel(merchantId, channelType, availableChannels);
            logger.info("切换到备用渠道: merchantId={}, channelType={}, backupChannelId={}",
                    merchantId, channelType, backupChannel != null ? backupChannel.getChannelId() : "none");
            return Optional.ofNullable(backupChannel);
        }
        
        return Optional.empty();
    }

    @Override
    public List<PaymentChannel> getAvailableChannels(ChannelType channelType) {
        List<PaymentChannel> allChannels = paymentChannelService.getAllActiveChannels();
        List<PaymentChannel> result = new ArrayList<>();
        
        for (PaymentChannel channel : allChannels) {
            if (channel.getChannelType() == channelType && channel.getStatus()) {
                result.add(channel);
            }
        }
        
        return result;
    }

    @Override
    public boolean shouldSwitchChannel(String merchantId, ChannelType channelType) {
        MerchantConfig config = merchantConfigService.getOrCreateDefaultConfig(merchantId);
        int failureCount = getFailureCount(merchantId, channelType);
        
        return merchantConfigService.shouldFailover(merchantId, channelType, failureCount);
    }

    @Override
    public void recordChannelFailure(String merchantId,
                                     String transactionId,
                                     ChannelType channelType,
                                     String failedChannelId,
                                     String reason) {
        int newFailureCount = incrementFailureCount(merchantId, channelType);
        
        logger.warn("记录渠道故障: merchantId={}, transactionId={}, channelType={}, channelId={}, failureCount={}, reason={}",
                merchantId, transactionId, channelType, failedChannelId, newFailureCount, reason);
        
        MerchantConfig config = merchantConfigService.getOrCreateDefaultConfig(merchantId);
        
        if (merchantConfigService.shouldFailover(merchantId, channelType, newFailureCount)) {
            Optional<PaymentChannel> backupChannel = getBackupChannel(merchantId, channelType);
            merchantConfigServiceImpl.recordFailover(
                    merchantId,
                    transactionId,
                    channelType,
                    failedChannelId,
                    backupChannel.map(PaymentChannel::getChannelId).orElse(null),
                    newFailureCount,
                    reason
            );
        }
    }

    @Override
    public void recordChannelRecovery(String merchantId, ChannelType channelType, String recoveredChannelId) {
        resetFailureCount(merchantId, channelType);
        logger.info("渠道恢复正常: merchantId={}, channelType={}, channelId={}",
                merchantId, channelType, recoveredChannelId);
    }

    @Override
    public MerchantConfig getMerchantFailoverConfig(String merchantId) {
        return merchantConfigService.getOrCreateDefaultConfig(merchantId);
    }

    private PaymentChannel findActivePrimaryChannel(String merchantId, 
                                                     ChannelType channelType, 
                                                     List<PaymentChannel> channels) {
        if (channels.isEmpty()) {
            return null;
        }
        
        MerchantConfig config = merchantConfigService.getOrCreateDefaultConfig(merchantId);
        int failureCount = getFailureCount(merchantId, channelType);
        
        if (merchantConfigService.shouldFailover(merchantId, channelType, failureCount)) {
            return findBackupChannel(merchantId, channelType, channels);
        }
        
        return channels.get(0);
    }

    private PaymentChannel findBackupChannel(String merchantId,
                                             ChannelType channelType,
                                             List<PaymentChannel> channels) {
        if (channels.size() <= 1) {
            return null;
        }
        
        for (int i = 1; i < channels.size(); i++) {
            if (channels.get(i).getStatus()) {
                return channels.get(i);
            }
        }
        
        return null;
    }

    private int getFailureCount(String merchantId, ChannelType channelType) {
        Map<ChannelType, Integer> channelCounts = channelFailureCounts.get(merchantId);
        if (channelCounts == null) {
            return 0;
        }
        Integer count = channelCounts.get(channelType);
        return count != null ? count : 0;
    }

    private int incrementFailureCount(String merchantId, ChannelType channelType) {
        Map<ChannelType, Integer> channelCounts = channelFailureCounts.computeIfAbsent(
                merchantId, k -> new ConcurrentHashMap<>());
        int newCount = channelCounts.merge(channelType, 1, Integer::sum);
        
        logger.debug("故障计数更新: merchantId={}, channelType={}, count={}",
                merchantId, channelType, newCount);
        
        return newCount;
    }

    private void resetFailureCount(String merchantId, ChannelType channelType) {
        Map<ChannelType, Integer> channelCounts = channelFailureCounts.get(merchantId);
        if (channelCounts != null) {
            channelCounts.put(channelType, 0);
        }
    }

    public void resetAllFailureCounts(String merchantId) {
        channelFailureCounts.remove(merchantId);
        logger.info("重置所有故障计数: merchantId={}", merchantId);
    }

    public Map<String, Integer> getFailureSummary(String merchantId) {
        Map<ChannelType, Integer> channelCounts = channelFailureCounts.get(merchantId);
        if (channelCounts == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Integer> summary = new HashMap<>();
        for (Map.Entry<ChannelType, Integer> entry : channelCounts.entrySet()) {
            summary.put(entry.getKey().name(), entry.getValue());
        }
        return summary;
    }
}
