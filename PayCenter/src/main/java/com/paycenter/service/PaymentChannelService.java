package com.paycenter.service;

import com.paycenter.entity.PaymentChannel;
import com.paycenter.enums.ChannelType;

import java.util.List;
import java.util.Optional;

public interface PaymentChannelService {
    PaymentChannel createChannel(PaymentChannel channel);
    PaymentChannel updateChannel(PaymentChannel channel);
    void deleteChannel(String channelId);
    Optional<PaymentChannel> getChannelById(String channelId);
    List<PaymentChannel> getAllActiveChannels();
    Optional<PaymentChannel> getChannelByType(ChannelType channelType);
    PaymentChannel getActiveChannelById(String channelId);
}
