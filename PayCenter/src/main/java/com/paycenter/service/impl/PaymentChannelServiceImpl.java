package com.paycenter.service.impl;

import com.paycenter.entity.PaymentChannel;
import com.paycenter.enums.ChannelType;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.PaymentChannelRepository;
import com.paycenter.service.PaymentChannelService;
import com.paycenter.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PaymentChannelServiceImpl implements PaymentChannelService {

    @Autowired
    private PaymentChannelRepository paymentChannelRepository;

    @Override
    @Transactional
    @CacheEvict(value = "channels", allEntries = true)
    public PaymentChannel createChannel(PaymentChannel channel) {
        if (channel.getChannelId() == null) {
            channel.setChannelId(IdGenerator.generateChannelId());
        }
        if (channel.getStatus() == null) {
            channel.setStatus(true);
        }
        return paymentChannelRepository.save(channel);
    }

    @Override
    @Transactional
    @CacheEvict(value = "channels", allEntries = true)
    public PaymentChannel updateChannel(PaymentChannel channel) {
        PaymentChannel existing = paymentChannelRepository.findById(channel.getChannelId())
                .orElseThrow(() -> new BusinessException("支付渠道不存在"));
        
        if (channel.getChannelName() != null) {
            existing.setChannelName(channel.getChannelName());
        }
        if (channel.getChannelType() != null) {
            existing.setChannelType(channel.getChannelType());
        }
        if (channel.getChannelConfig() != null) {
            existing.setChannelConfig(channel.getChannelConfig());
        }
        if (channel.getFeeRate() != null) {
            existing.setFeeRate(channel.getFeeRate());
        }
        if (channel.getStatus() != null) {
            existing.setStatus(channel.getStatus());
        }
        
        return paymentChannelRepository.save(existing);
    }

    @Override
    @Transactional
    @CacheEvict(value = "channels", allEntries = true)
    public void deleteChannel(String channelId) {
        PaymentChannel channel = paymentChannelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException("支付渠道不存在"));
        channel.setStatus(false);
        paymentChannelRepository.save(channel);
    }

    @Override
    @Cacheable(value = "channels", key = "#channelId")
    public Optional<PaymentChannel> getChannelById(String channelId) {
        return paymentChannelRepository.findById(channelId);
    }

    @Override
    @Cacheable(value = "channels", key = "'all'")
    public List<PaymentChannel> getAllActiveChannels() {
        return paymentChannelRepository.findByStatusTrue();
    }

    @Override
    @Cacheable(value = "channels", key = "'type_' + #channelType")
    public Optional<PaymentChannel> getChannelByType(ChannelType channelType) {
        return paymentChannelRepository.findByChannelTypeAndStatusTrue(channelType);
    }

    @Override
    public PaymentChannel getActiveChannelById(String channelId) {
        return paymentChannelRepository.findByChannelIdAndStatusTrue(channelId)
                .orElseThrow(() -> new BusinessException("支付渠道不存在或已停用"));
    }
}
