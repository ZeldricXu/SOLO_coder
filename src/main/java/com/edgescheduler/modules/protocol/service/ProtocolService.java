package com.edgescheduler.modules.protocol.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.modules.protocol.domain.DataForwardRule;
import com.edgescheduler.modules.protocol.domain.ProtocolDriver;
import com.edgescheduler.modules.protocol.mapper.DataForwardRuleMapper;
import com.edgescheduler.modules.protocol.mapper.ProtocolDriverMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolService {

    private final ProtocolDriverMapper protocolDriverMapper;
    private final DataForwardRuleMapper dataForwardRuleMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final Map<String, ProtocolDriver> loadedDrivers = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public Mono<ProtocolDriver> registerDriver(ProtocolDriver driver) {
        ProtocolDriver existing = protocolDriverMapper.selectOne(
                new LambdaQueryWrapper<ProtocolDriver>()
                        .eq(ProtocolDriver::getProtocolType, driver.getProtocolType())
                        .eq(ProtocolDriver::getDriverName, driver.getDriverName()));
        if (existing != null) {
            return Mono.error(new BusinessException("驱动已存在"));
        }

        driver.setDriverId(IdGenerator.generateId("drv"));
        driver.setLoadStatus("UNLOADED");
        driver.setEnabled(true);
        protocolDriverMapper.insert(driver);
        updateMetrics("driver_registered");
        return Mono.just(driver);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<ProtocolDriver> loadDriver(String driverId) {
        ProtocolDriver driver = protocolDriverMapper.selectOne(
                new LambdaQueryWrapper<ProtocolDriver>().eq(ProtocolDriver::getDriverId, driverId));
        if (driver == null) {
            return Mono.error(new BusinessException("驱动不存在"));
        }

        if (!driver.getEnabled()) {
            return Mono.error(new BusinessException("驱动已禁用"));
        }

        try {
            loadDriverClass(driver);
            driver.setLoadStatus("LOADED");
            driver.setLoadedAt(LocalDateTime.now());
            protocolDriverMapper.updateById(driver);
            loadedDrivers.put(driverId, driver);

            redisTemplate.opsForSet().add("protocol:loaded_drivers", driverId).subscribe();
            updateMetrics("driver_loaded");
            return Mono.just(driver);
        } catch (Exception e) {
            log.error("Failed to load driver: {}", driverId, e);
            driver.setLoadStatus("FAILED");
            protocolDriverMapper.updateById(driver);
            return Mono.error(new BusinessException("驱动加载失败: " + e.getMessage()));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<ProtocolDriver> unloadDriver(String driverId) {
        ProtocolDriver driver = protocolDriverMapper.selectOne(
                new LambdaQueryWrapper<ProtocolDriver>().eq(ProtocolDriver::getDriverId, driverId));
        if (driver == null) {
            return Mono.error(new BusinessException("驱动不存在"));
        }

        try {
            unloadDriverClass(driver);
            driver.setLoadStatus("UNLOADED");
            protocolDriverMapper.updateById(driver);
            loadedDrivers.remove(driverId);

            redisTemplate.opsForSet().remove("protocol:loaded_drivers", driverId).subscribe();
            updateMetrics("driver_unloaded");
            return Mono.just(driver);
        } catch (Exception e) {
            log.error("Failed to unload driver: {}", driverId, e);
            return Mono.error(new BusinessException("驱动卸载失败: " + e.getMessage()));
        }
    }

    public Mono<Map<String, Object>> convertData(String sourceProtocol, Map<String, Object> rawData) {
        ProtocolDriver driver = loadedDrivers.values().stream()
                .filter(d -> d.getProtocolType().equals(sourceProtocol) && "LOADED".equals(d.getLoadStatus()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("协议驱动未加载: " + sourceProtocol));

        return Mono.fromCallable(() -> {
            Map<String, Object> normalizedData = normalizeData(rawData, driver);
            Map<String, Object> result = new HashMap<>();
            result.put("normalizedData", normalizedData);
            result.put("sourceProtocol", sourceProtocol);
            result.put("convertedAt", System.currentTimeMillis());
            result.put("driverId", driver.getDriverId());

            applyForwardRules(normalizedData, sourceProtocol);
            updateMetrics("data_converted");

            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DataForwardRule> createForwardRule(DataForwardRule rule) {
        rule.setRuleId(IdGenerator.generateRuleId());
        rule.setEnabled(true);
        rule.setForwardCount(0L);
        dataForwardRuleMapper.insert(rule);
        updateMetrics("forward_rule_created");
        return Mono.just(rule);
    }

    public Mono<Map<String, Object>> forwardData(String sourceProtocol, String sourceTopic,
                                                   Map<String, Object> normalizedData) {
        List<DataForwardRule> rules = dataForwardRuleMapper.selectList(
                new LambdaQueryWrapper<DataForwardRule>()
                        .eq(DataForwardRule::getSourceProtocol, sourceProtocol)
                        .eq(DataForwardRule::getSourceTopic, sourceTopic)
                        .eq(DataForwardRule::getEnabled, true));

        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> forwardedTargets = new ArrayList<>();

            for (DataForwardRule rule : rules) {
                Map<String, Object> targetData = applyDataMapping(normalizedData, rule.getDataMapping());

                sendToTarget(rule.getTargetProtocol(), rule.getTargetTopic(), targetData, rule.getQos());

                rule.setForwardCount(rule.getForwardCount() + 1);
                rule.setLastForwardTime(LocalDateTime.now());
                dataForwardRuleMapper.updateById(rule);

                Map<String, Object> targetInfo = new HashMap<>();
                targetInfo.put("targetProtocol", rule.getTargetProtocol());
                targetInfo.put("targetTopic", rule.getTargetTopic());
                targetInfo.put("forwardMode", rule.getForwardMode());
                forwardedTargets.add(targetInfo);
            }

            result.put("forwardedTargets", forwardedTargets);
            result.put("ruleCount", rules.size());
            updateMetrics("data_forwarded");

            return result;
        });
    }

    private Map<String, Object> normalizeData(Map<String, Object> rawData, ProtocolDriver driver) {
        Map<String, Object> normalized = new HashMap<>();

        Map<String, Object> mapping = (Map<String, Object>) driver.getConnectionParams().get("dataMapping");
        if (mapping != null) {
            mapping.forEach((sourceKey, targetKey) -> {
                if (rawData.containsKey(sourceKey)) {
                    normalized.put((String) targetKey, rawData.get(sourceKey));
                }
            });
        } else {
            normalized.putAll(rawData);
        }

        normalized.put("_protocol", driver.getProtocolType());
        normalized.put("_driver", driver.getDriverId());
        normalized.put("_timestamp", System.currentTimeMillis());

        return normalized;
    }

    private void applyForwardRules(Map<String, Object> normalizedData, String sourceProtocol) {
        List<DataForwardRule> rules = dataForwardRuleMapper.selectList(
                new LambdaQueryWrapper<DataForwardRule>()
                        .eq(DataForwardRule::getSourceProtocol, sourceProtocol)
                        .eq(DataForwardRule::getEnabled, true));

        for (DataForwardRule rule : rules) {
            Map<String, Object> targetData = applyDataMapping(normalizedData, rule.getDataMapping());
            sendToTarget(rule.getTargetProtocol(), rule.getTargetTopic(), targetData, rule.getQos());

            rule.setForwardCount(rule.getForwardCount() + 1);
            rule.setLastForwardTime(LocalDateTime.now());
            dataForwardRuleMapper.updateById(rule);
        }
    }

    private Map<String, Object> applyDataMapping(Map<String, Object> sourceData, Map<String, Object> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return sourceData;
        }

        Map<String, Object> result = new HashMap<>();
        mapping.forEach((sourceKey, targetKey) -> {
            if (sourceData.containsKey(sourceKey)) {
                result.put((String) targetKey, sourceData.get(sourceKey));
            }
        });

        return result;
    }

    private void sendToTarget(String targetProtocol, String targetTopic, Map<String, Object> data, Integer qos) {
        redisTemplate.convertAndSend(
                "protocol:" + targetProtocol + ":" + targetTopic,
                data
        ).subscribe();
    }

    private void loadDriverClass(ProtocolDriver driver) throws Exception {
        log.info("Loading driver: {} ({})", driver.getDriverName(), driver.getDriverClass());
        Thread.sleep(100);
    }

    private void unloadDriverClass(ProtocolDriver driver) throws Exception {
        log.info("Unloading driver: {} ({})", driver.getDriverName(), driver.getDriverClass());
        Thread.sleep(50);
    }

    public Flux<ProtocolDriver> getDrivers(String protocolType) {
        List<ProtocolDriver> drivers = protocolDriverMapper.selectList(
                new LambdaQueryWrapper<ProtocolDriver>()
                        .eq(protocolType != null, ProtocolDriver::getProtocolType, protocolType)
                        .orderByDesc(ProtocolDriver::getCreatedAt));
        return Flux.fromIterable(drivers);
    }

    public Flux<DataForwardRule> getForwardRules(String sourceProtocol) {
        List<DataForwardRule> rules = dataForwardRuleMapper.selectList(
                new LambdaQueryWrapper<DataForwardRule>()
                        .eq(sourceProtocol != null, DataForwardRule::getSourceProtocol, sourceProtocol)
                        .orderByDesc(DataForwardRule::getCreatedAt));
        return Flux.fromIterable(rules);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<DataForwardRule> toggleForwardRule(String ruleId, boolean enabled) {
        DataForwardRule rule = dataForwardRuleMapper.selectOne(
                new LambdaQueryWrapper<DataForwardRule>().eq(DataForwardRule::getRuleId, ruleId));
        if (rule == null) {
            return Mono.error(new BusinessException("转发规则不存在"));
        }

        rule.setEnabled(enabled);
        dataForwardRuleMapper.updateById(rule);
        return Mono.just(rule);
    }

    private void updateMetrics(String action) {
        meterRegistry.counter("edge_scheduler_protocol_operations_total", "action", action).increment();
    }
}
