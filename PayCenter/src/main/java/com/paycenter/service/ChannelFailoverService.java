package com.paycenter.service;

import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.enums.ChannelType;

import java.util.List;
import java.util.Optional;

public interface ChannelFailoverService {
    Optional<PaymentChannel> getPrimaryChannel(String merchantId, ChannelType channelType);
    Optional<PaymentChannel> getBackupChannel(String merchantId, ChannelType channelType);
    List<PaymentChannel> getAvailableChannels(ChannelType channelType);
    boolean shouldSwitchChannel(String merchantId, ChannelType channelType);
    void recordChannelFailure(String merchantId,
                              String transactionId,
                              ChannelType channelType,
                              String failedChannelId,
                              String reason);
    void recordChannelRecovery(String merchantId, ChannelType channelType, String recoveredChannelId);
    MerchantConfig getMerchantFailoverConfig(String merchantId);
}
