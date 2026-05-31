package com.solocoder.dns.traffic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.enums.TrafficStrategy;
import com.solocoder.dns.common.exception.ResourceNotFoundException;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.traffic.model.*;
import com.solocoder.dns.persistence.entity.TrafficStrategyPO;
import com.solocoder.dns.persistence.mapper.TrafficStrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficStrategyService {
    private final TrafficStrategyMapper strategyMapper;

    public TrafficStrategy createStrategy(TrafficStrategy strategy) {
        strategy.setStrategyId(IdGenerator.generateId("strategy"));
        strategy.setCreatedAt(LocalDateTime.now());
        strategy.setUpdatedAt(LocalDateTime.now());
        strategy.setEnabled(strategy.getEnabled() != null ? strategy.getEnabled() : true);
        strategyMapper.insert(toPO(strategy));
        log.info("Traffic strategy created: {} ({})", strategy.getStrategyId(), strategy.getStrategyType());
        return strategy;
    }

    public TrafficStrategy updateStrategy(TrafficStrategy strategy) {
        TrafficStrategyPO existing = strategyMapper.selectById(strategy.getStrategyId());
        if (existing == null) {
            throw new ResourceNotFoundException("TrafficStrategy", strategy.getStrategyId());
        }
        strategy.setUpdatedAt(LocalDateTime.now());
        strategyMapper.updateById(toPO(strategy));
        log.info("Traffic strategy updated: {}", strategy.getStrategyId());
        return strategy;
    }

    public TrafficStrategy getStrategy(String strategyId) {
        TrafficStrategyPO po = strategyMapper.selectById(strategyId);
        if (po == null) {
            throw new ResourceNotFoundException("TrafficStrategy", strategyId);
        }
        return toDomain(po);
    }

    public PageResult<TrafficStrategy> listStrategies(int page, int size, String strategyType) {
        LambdaQueryWrapper<TrafficStrategyPO> wrapper = new LambdaQueryWrapper<>();
        if (strategyType != null && !strategyType.isEmpty()) {
            wrapper.eq(TrafficStrategyPO::getStrategyType, strategyType);
        }
        wrapper.orderByDesc(TrafficStrategyPO::getCreatedAt);
        Page<TrafficStrategyPO> poPage = strategyMapper.selectPage(new Page<>(page, size), wrapper);
        List<TrafficStrategy> items = poPage.getRecords().stream().map(this::toDomain).collect(Collectors.toList());
        return new PageResult<>(items, poPage.getTotal(), page, size);
    }

    public void deleteStrategy(String strategyId) {
        strategyMapper.deleteById(strategyId);
        log.info("Traffic strategy deleted: {}", strategyId);
    }

    public List<TrafficStrategy> getEnabledStrategies(String targetService) {
        LambdaQueryWrapper<TrafficStrategyPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TrafficStrategyPO::getEnabled, true);
        if (targetService != null && !targetService.isEmpty()) {
            wrapper.eq(TrafficStrategyPO::getTargetService, targetService);
        }
        return strategyMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public boolean shouldRouteToCanary(String clientId, String headerValue, CanaryConfig config) {
        if (config.getTrafficPercent() != null && config.getTrafficPercent() > 0) {
            int hash = Math.abs(clientId.hashCode() % 100);
            return hash < config.getTrafficPercent();
        }
        if (config.getHeaderValue() != null && config.getHeaderValue().equals(headerValue)) {
            return true;
        }
        return false;
    }

    public String selectBlueGreenVersion(BlueGreenConfig config) {
        if (config.getTrafficPercentToGreen() == null || config.getTrafficPercentToGreen() == 0) {
            return config.getBlueVersion();
        }
        if (config.getTrafficPercentToGreen() == 100) {
            return config.getGreenVersion();
        }
        int random = (int) (Math.random() * 100);
        return random < config.getTrafficPercentToGreen() ? config.getGreenVersion() : config.getBlueVersion();
    }

    public boolean shouldMirrorRequest(TrafficMirrorConfig config) {
        if (config.getTrafficPercent() == null || config.getTrafficPercent() == 0) {
            return false;
        }
        if (config.getTrafficPercent() == 100) {
            return true;
        }
        return (int) (Math.random() * 100) < config.getTrafficPercent();
    }

    private TrafficStrategyPO toPO(TrafficStrategy strategy) {
        TrafficStrategyPO po = new TrafficStrategyPO();
        po.setStrategyId(strategy.getStrategyId());
        po.setStrategyType(strategy.getStrategyType());
        po.setName(strategy.getName());
        po.setDescription(strategy.getDescription());
        po.setRules(JsonUtils.toJson(strategy.getRules()));
        po.setTargetService(strategy.getTargetService());
        po.setTrafficPercent(strategy.getTrafficPercent());
        po.setEnabled(strategy.getEnabled());
        po.setCreatedAt(strategy.getCreatedAt());
        po.setUpdatedAt(strategy.getUpdatedAt());
        return po;
    }

    @SuppressWarnings("unchecked")
    private TrafficStrategy toDomain(TrafficStrategyPO po) {
        TrafficStrategy strategy = new TrafficStrategy();
        strategy.setStrategyId(po.getStrategyId());
        strategy.setStrategyType(po.getStrategyType());
        strategy.setName(po.getName());
        strategy.setDescription(po.getDescription());
        strategy.setRules(JsonUtils.fromJson(po.getRules(), Map.class));
        strategy.setTargetService(po.getTargetService());
        strategy.setTrafficPercent(po.getTrafficPercent());
        strategy.setEnabled(po.getEnabled());
        strategy.setCreatedAt(po.getCreatedAt());
        strategy.setUpdatedAt(po.getUpdatedAt());
        return strategy;
    }
}
