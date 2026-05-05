package com.paygateway.service;

import com.paygateway.entity.ChannelConfig;
import com.paygateway.exception.BusinessException;
import com.paygateway.repository.ChannelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelConfigService {
    
    private final ChannelConfigRepository channelConfigRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String CACHE_PREFIX = "channel:config:";
    
    public ChannelConfig getByMerchantIdAndChannel(String merchantId, String channel) {
        String cacheKey = CACHE_PREFIX + merchantId + ":" + channel;
        
        ChannelConfig cached = (ChannelConfig) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        Optional<ChannelConfig> configOpt = channelConfigRepository.findByMerchantIdAndChannel(merchantId, channel);
        if (configOpt.isEmpty()) {
            throw new BusinessException(404, "渠道配置不存在：merchantId=" + merchantId + ", channel=" + channel);
        }
        
        ChannelConfig config = configOpt.get();
        if (!config.getEnabled()) {
            throw new BusinessException(400, "渠道未启用：" + channel);
        }
        
        redisTemplate.opsForValue().set(cacheKey, config, 5, TimeUnit.MINUTES);
        return config;
    }
    
    public List<ChannelConfig> getByMerchantId(String merchantId) {
        return channelConfigRepository.findByMerchantId(merchantId);
    }
    
    @Transactional
    public ChannelConfig save(ChannelConfig config) {
        if (channelConfigRepository.existsByMerchantIdAndChannel(config.getMerchantId(), config.getChannel())) {
            throw new BusinessException(400, "该商户渠道配置已存在");
        }
        
        ChannelConfig saved = channelConfigRepository.save(config);
        evictCache(config.getMerchantId(), config.getChannel());
        return saved;
    }
    
    @Transactional
    public ChannelConfig update(Long id, ChannelConfig config) {
        Optional<ChannelConfig> existingOpt = channelConfigRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new BusinessException(404, "渠道配置不存在");
        }
        
        ChannelConfig existing = existingOpt.get();
        
        if (config.getChannelMerchantId() != null) {
            existing.setChannelMerchantId(config.getChannelMerchantId());
        }
        if (config.getAppId() != null) {
            existing.setAppId(config.getAppId());
        }
        if (config.getPrivateKey() != null) {
            existing.setPrivateKey(config.getPrivateKey());
        }
        if (config.getPublicKey() != null) {
            existing.setPublicKey(config.getPublicKey());
        }
        if (config.getNotifyUrl() != null) {
            existing.setNotifyUrl(config.getNotifyUrl());
        }
        if (config.getEnabled() != null) {
            existing.setEnabled(config.getEnabled());
        }
        if (config.getPriority() != null) {
            existing.setPriority(config.getPriority());
        }
        
        ChannelConfig saved = channelConfigRepository.save(existing);
        evictCache(existing.getMerchantId(), existing.getChannel());
        return saved;
    }
    
    @Transactional
    public void delete(Long id) {
        Optional<ChannelConfig> configOpt = channelConfigRepository.findById(id);
        if (configOpt.isPresent()) {
            ChannelConfig config = configOpt.get();
            channelConfigRepository.deleteById(id);
            evictCache(config.getMerchantId(), config.getChannel());
        }
    }
    
    private void evictCache(String merchantId, String channel) {
        String cacheKey = CACHE_PREFIX + merchantId + ":" + channel;
        redisTemplate.delete(cacheKey);
    }
}
