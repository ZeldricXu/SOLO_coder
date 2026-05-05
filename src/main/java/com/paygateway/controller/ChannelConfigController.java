package com.paygateway.controller;

import com.paygateway.dto.ApiResponse;
import com.paygateway.entity.ChannelConfig;
import com.paygateway.service.ChannelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ChannelConfigController {
    
    private final ChannelConfigService channelConfigService;
    
    @GetMapping("/list/{merchantId}")
    public ApiResponse<List<ChannelConfig>> listByMerchantId(@PathVariable String merchantId) {
        log.info("查询商户渠道配置：merchantId={}", merchantId);
        
        List<ChannelConfig> configs = channelConfigService.getByMerchantId(merchantId);
        
        return ApiResponse.success(configs);
    }
    
    @GetMapping("/{merchantId}/{channel}")
    public ApiResponse<ChannelConfig> getByChannel(
            @PathVariable String merchantId,
            @PathVariable String channel) {
        
        log.info("查询商户指定渠道配置：merchantId={}, channel={}", merchantId, channel);
        
        ChannelConfig config = channelConfigService.getByMerchantIdAndChannel(merchantId, channel);
        
        return ApiResponse.success(config);
    }
    
    @PostMapping
    public ApiResponse<ChannelConfig> create(@RequestBody ChannelConfig config) {
        log.info("创建渠道配置：merchantId={}, channel={}", config.getMerchantId(), config.getChannel());
        
        ChannelConfig saved = channelConfigService.save(config);
        
        log.info("渠道配置创建成功：id={}", saved.getId());
        
        return ApiResponse.success(saved);
    }
    
    @PutMapping("/{id}")
    public ApiResponse<ChannelConfig> update(@PathVariable Long id, @RequestBody ChannelConfig config) {
        log.info("更新渠道配置：id={}", id);
        
        ChannelConfig updated = channelConfigService.update(id, config);
        
        log.info("渠道配置更新成功：id={}", updated.getId());
        
        return ApiResponse.success(updated);
    }
    
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        log.info("删除渠道配置：id={}", id);
        
        channelConfigService.delete(id);
        
        log.info("渠道配置删除成功：id={}", id);
        
        return ApiResponse.success();
    }
}
