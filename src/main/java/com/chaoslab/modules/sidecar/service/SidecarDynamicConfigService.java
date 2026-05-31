package com.chaoslab.modules.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.JsonUtils;
import com.chaoslab.entity.*;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.*;
import com.chaoslab.modules.sidecar.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarDynamicConfigService {

    private final DynamicConfigMapper dynamicConfigMapper;
    private final ConfigTemplateMapper configTemplateMapper;
    private final ConfigChangeLogMapper configChangeLogMapper;
    private final SidecarInstanceMapper sidecarInstanceMapper;
    private final SidecarConfigMapper sidecarConfigMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, DynamicConfig> configCache = new ConcurrentHashMap<>();
    private final Map<String, List<ConfigTemplate>> templateCache = new ConcurrentHashMap<>();

    // ==================== 动态配置管理 ====================

    @Transactional
    public Mono<DynamicConfig> createDynamicConfig(DynamicConfigCreateRequest request) {
        return Mono.fromCallable(() -> {
            validateConfigKey(request.getConfigKey());

            LambdaQueryWrapper<DynamicConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DynamicConfig::getConfigKey, request.getConfigKey());
            if (dynamicConfigMapper.selectCount(wrapper) > 0) {
                throw BusinessException.validationError("配置键已存在: " + request.getConfigKey());
            }

            DynamicConfig config = new DynamicConfig();
            config.setConfigId("dc-" + UUID.randomUUID().toString().substring(0, 8));
            config.setConfigKey(request.getConfigKey());
            config.setConfigName(request.getConfigName());
            config.setConfigType(request.getConfigType());
            config.setDescription(request.getDescription());
            config.setConfigValue(request.getConfigValue());
            config.setDefaultValue(request.getDefaultValue());
            config.setValidationRule(request.getValidationRule());
            config.setEnabled(true);
            config.setHotReloadable(request.getHotReloadable() != null ? request.getHotReloadable() : true);
            config.setScope(request.getScope());
            config.setLastModifiedBy("system");
            config.setLastModifiedAt(LocalDateTime.now());
            config.setVersion(1);

            dynamicConfigMapper.insert(config);
            configCache.put(config.getConfigKey(), config);

            log.info("Created dynamic config: {} for key: {}", config.getConfigId(), request.getConfigKey());
            return config;
        });
    }

    @Transactional
    public Mono<DynamicConfig> updateDynamicConfig(DynamicConfigUpdateRequest request) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DynamicConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DynamicConfig::getConfigId, request.getConfigId());
            DynamicConfig config = dynamicConfigMapper.selectOne(wrapper);
            if (config == null) {
                throw BusinessException.notFound("动态配置不存在: " + request.getConfigId());
            }

            validateConfigValue(request.getConfigValue(), config.getValidationRule());

            Map<String, Object> oldValue = config.getConfigValue();

            ConfigChangeLog changeLog = new ConfigChangeLog();
            changeLog.setLogId("ccl-" + UUID.randomUUID().toString().substring(0, 8));
            changeLog.setConfigId(config.getConfigId());
            changeLog.setConfigKey(config.getConfigKey());
            changeLog.setOldValue(oldValue);
            changeLog.setNewValue(request.getConfigValue());
            changeLog.setChangeType("UPDATE");
            changeLog.setChangedBy(request.getChangedBy());
            changeLog.setChangedAt(LocalDateTime.now());
            changeLog.setChangeReason(request.getChangeReason());
            changeLog.setStatus("PENDING");
            changeLog.setRollbackStatus("NONE");
            configChangeLogMapper.insert(changeLog);

            config.setConfigValue(request.getConfigValue());
            config.setLastModifiedBy(request.getChangedBy());
            config.setLastModifiedAt(LocalDateTime.now());
            config.setVersion(config.getVersion() + 1);
            dynamicConfigMapper.updateById(config);

            configCache.put(config.getConfigKey(), config);
            changeLog.setStatus("APPLIED");
            configChangeLogMapper.updateById(changeLog);

            if (Boolean.TRUE.equals(config.getHotReloadable())) {
                publishConfigChangeEvent(config, oldValue, request.getConfigValue());
            }

            log.info("Updated dynamic config: {}, version: {}", config.getConfigId(), config.getVersion());
            return config;
        });
    }

    public Mono<DynamicConfig> getDynamicConfig(String configKey) {
        return Mono.fromCallable(() -> {
            DynamicConfig cached = configCache.get(configKey);
            if (cached != null && Boolean.TRUE.equals(cached.getEnabled())) {
                return cached;
            }

            LambdaQueryWrapper<DynamicConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DynamicConfig::getConfigKey, configKey)
                    .eq(DynamicConfig::getEnabled, true);
            DynamicConfig config = dynamicConfigMapper.selectOne(wrapper);
            if (config == null) {
                throw BusinessException.notFound("动态配置不存在: " + configKey);
            }

            configCache.put(configKey, config);
            return config;
        });
    }

    public Mono<List<DynamicConfig>> listDynamicConfigs(String scope, String configType) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DynamicConfig> wrapper = new LambdaQueryWrapper<>();
            if (scope != null && !scope.isEmpty()) {
                wrapper.eq(DynamicConfig::getScope, scope);
            }
            if (configType != null && !configType.isEmpty()) {
                wrapper.eq(DynamicConfig::getConfigType, configType);
            }
            wrapper.eq(DynamicConfig::getEnabled, true)
                    .orderByDesc(DynamicConfig::getCreatedAt);
            return dynamicConfigMapper.selectList(wrapper);
        });
    }

    @Transactional
    public Mono<Void> rollbackConfig(String logId) {
        return Mono.fromRunnable(() -> {
            LambdaQueryWrapper<ConfigChangeLog> logWrapper = new LambdaQueryWrapper<>();
            logWrapper.eq(ConfigChangeLog::getLogId, logId);
            ConfigChangeLog changeLog = configChangeLogMapper.selectOne(logWrapper);
            if (changeLog == null) {
                throw BusinessException.notFound("变更日志不存在: " + logId);
            }
            if ("ROLLED_BACK".equals(changeLog.getRollbackStatus())) {
                throw BusinessException.validationError("该变更已回滚");
            }

            LambdaQueryWrapper<DynamicConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(DynamicConfig::getConfigId, changeLog.getConfigId());
            DynamicConfig config = dynamicConfigMapper.selectOne(configWrapper);
            if (config == null) {
                throw BusinessException.notFound("配置不存在");
            }

            Map<String, Object> oldValue = config.getConfigValue();
            config.setConfigValue(changeLog.getOldValue());
            config.setLastModifiedBy("rollback");
            config.setLastModifiedAt(LocalDateTime.now());
            config.setVersion(config.getVersion() + 1);
            dynamicConfigMapper.updateById(config);

            changeLog.setRollbackStatus("ROLLED_BACK");
            configChangeLogMapper.updateById(changeLog);

            configCache.put(config.getConfigKey(), config);

            if (Boolean.TRUE.equals(config.getHotReloadable())) {
                publishConfigChangeEvent(config, oldValue, changeLog.getOldValue());
            }

            log.info("Rolled back config: {} to version before change: {}", config.getConfigId(), logId);
        });
    }

    // ==================== 配置模板管理 ====================

    @Transactional
    public Mono<ConfigTemplate> createConfigTemplate(ConfigTemplateCreateRequest request) {
        return Mono.fromCallable(() -> {
            ConfigTemplate template = new ConfigTemplate();
            template.setTemplateId("ct-" + UUID.randomUUID().toString().substring(0, 8));
            template.setTemplateName(request.getTemplateName());
            template.setTemplateType(request.getTemplateType());
            template.setScenario(request.getScenario());
            template.setDescription(request.getDescription());
            template.setConfigData(request.getConfigData());
            template.setResourceLimits(request.getResourceLimits());
            template.setEnabled(true);
            template.setPriority(request.getPriority() != null ? request.getPriority() : 0);
            template.setCreatedBy("system");
            template.setCreatedAt(LocalDateTime.now());
            template.setUpdatedAt(LocalDateTime.now());
            template.setVersion(1);

            configTemplateMapper.insert(template);
            invalidateTemplateCache(template.getScenario());

            log.info("Created config template: {} for scenario: {}", template.getTemplateId(), template.getScenario());
            return template;
        });
    }

    public Mono<List<ConfigTemplate>> getTemplatesByScenario(String scenario) {
        return Mono.fromCallable(() -> {
            List<ConfigTemplate> cached = templateCache.get(scenario);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }

            LambdaQueryWrapper<ConfigTemplate> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ConfigTemplate::getScenario, scenario)
                    .eq(ConfigTemplate::getEnabled, true)
                    .orderByAsc(ConfigTemplate::getPriority);
            List<ConfigTemplate> templates = configTemplateMapper.selectList(wrapper);

            templateCache.put(scenario, templates);
            return templates;
        });
    }

    @Transactional
    public Mono<SidecarConfig> applyTemplateToInstance(ConfigApplyRequest request) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInstance> instanceWrapper = new LambdaQueryWrapper<>();
            instanceWrapper.eq(SidecarInstance::getInstanceId, request.getInstanceId());
            SidecarInstance instance = sidecarInstanceMapper.selectOne(instanceWrapper);
            if (instance == null) {
                throw BusinessException.notFound("Sidecar实例不存在: " + request.getInstanceId());
            }

            LambdaQueryWrapper<ConfigTemplate> templateWrapper = new LambdaQueryWrapper<>();
            templateWrapper.eq(ConfigTemplate::getTemplateId, request.getTemplateId())
                    .eq(ConfigTemplate::getEnabled, true);
            ConfigTemplate template = configTemplateMapper.selectOne(templateWrapper);
            if (template == null) {
                throw BusinessException.notFound("配置模板不存在: " + request.getTemplateId());
            }

            Map<String, Object> configData = new HashMap<>();
            if (template.getConfigData() != null) {
                configData.putAll(template.getConfigData());
            }
            if (template.getResourceLimits() != null) {
                configData.put("resources", template.getResourceLimits());
            }
            configData.put("appliedFromTemplate", template.getTemplateId());
            configData.put("appliedBy", request.getAppliedBy());
            configData.put("appliedAt", LocalDateTime.now().toString());
            configData.put("reason", request.getReason());

            LambdaQueryWrapper<SidecarConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(SidecarConfig::getInstanceId, request.getInstanceId())
                    .orderByDesc(SidecarConfig::getVersion)
                    .last("LIMIT 1");
            SidecarConfig latestConfig = sidecarConfigMapper.selectOne(configWrapper);

            int newVersion = latestConfig != null ? latestConfig.getVersion() + 1 : 1;

            SidecarConfig newConfig = new SidecarConfig();
            newConfig.setConfigId("sc-" + UUID.randomUUID().toString().substring(0, 8));
            newConfig.setInstanceId(request.getInstanceId());
            newConfig.setConfigData(configData);
            newConfig.setVersion(newVersion);
            newConfig.setApplied(false);
            newConfig.setAppliedAt(null);

            sidecarConfigMapper.insert(newConfig);

            instance.setStatus("config_pending");
            sidecarInstanceMapper.updateById(instance);

            publishConfigAppliedEvent(instance, template, newConfig);

            log.info("Applied template: {} to instance: {}, new config version: {}",
                    template.getTemplateId(), request.getInstanceId(), newVersion);
            return newConfig;
        });
    }

    public Flux<SidecarConfig> applyTemplateToNamespace(String namespace, String templateId, String appliedBy, String reason) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInstance> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarInstance::getNamespace, namespace)
                    .eq(SidecarInstance::getStatus, "running");
            return sidecarInstanceMapper.selectList(wrapper);
        }).flatMapMany(instances -> Flux.fromIterable(instances)
                .flatMap(instance -> {
                    ConfigApplyRequest request = new ConfigApplyRequest();
                    request.setInstanceId(instance.getInstanceId());
                    request.setTemplateId(templateId);
                    request.setAppliedBy(appliedBy);
                    request.setReason(reason);
                    return applyTemplateToInstance(request)
                            .subscribeOn(Schedulers.boundedElastic());
                }, 4));
    }

    // ==================== 热更新相关 ====================

    public Mono<Map<String, Object>> getEffectiveConfig(String instanceId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInstance> instanceWrapper = new LambdaQueryWrapper<>();
            instanceWrapper.eq(SidecarInstance::getInstanceId, instanceId);
            SidecarInstance instance = sidecarInstanceMapper.selectOne(instanceWrapper);
            if (instance == null) {
                throw BusinessException.notFound("Sidecar实例不存在: " + instanceId);
            }

            Map<String, Object> effectiveConfig = new HashMap<>();

            LambdaQueryWrapper<DynamicConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(DynamicConfig::getEnabled, true)
                    .and(w -> w.eq(DynamicConfig::getScope, "global")
                            .or()
                            .eq(DynamicConfig::getScope, instance.getNamespace()));
            List<DynamicConfig> configs = dynamicConfigMapper.selectList(configWrapper);

            for (DynamicConfig config : configs) {
                effectiveConfig.put(config.getConfigKey(), config.getConfigValue());
            }

            LambdaQueryWrapper<SidecarConfig> sidecarConfigWrapper = new LambdaQueryWrapper<>();
            sidecarConfigWrapper.eq(SidecarConfig::getInstanceId, instanceId)
                    .eq(SidecarConfig::getApplied, true)
                    .orderByDesc(SidecarConfig::getVersion)
                    .last("LIMIT 1");
            SidecarConfig appliedConfig = sidecarConfigMapper.selectOne(sidecarConfigWrapper);
            if (appliedConfig != null && appliedConfig.getConfigData() != null) {
                effectiveConfig.putAll(appliedConfig.getConfigData());
            }

            return effectiveConfig;
        });
    }

    public Mono<Void> refreshConfigCache() {
        return Mono.fromRunnable(() -> {
            configCache.clear();
            templateCache.clear();

            LambdaQueryWrapper<DynamicConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(DynamicConfig::getEnabled, true);
            List<DynamicConfig> configs = dynamicConfigMapper.selectList(configWrapper);
            for (DynamicConfig config : configs) {
                configCache.put(config.getConfigKey(), config);
            }

            LambdaQueryWrapper<ConfigTemplate> templateWrapper = new LambdaQueryWrapper<>();
            templateWrapper.eq(ConfigTemplate::getEnabled, true);
            List<ConfigTemplate> templates = configTemplateMapper.selectList(templateWrapper);
            for (ConfigTemplate template : templates) {
                templateCache.computeIfAbsent(template.getScenario(), k -> new ArrayList<>()).add(template);
            }

            log.info("Refreshed config cache: {} configs, {} templates", configCache.size(), templateCache.size());
        });
    }

    // ==================== 私有方法 ====================

    private void validateConfigKey(String configKey) {
        if (configKey == null || configKey.isEmpty()) {
            throw BusinessException.validationError("配置键不能为空");
        }
        if (!Pattern.matches("^[a-zA-Z][a-zA-Z0-9._-]*$", configKey)) {
            throw BusinessException.validationError("配置键格式不正确，只能包含字母、数字、点、下划线和横杠");
        }
    }

    private void validateConfigValue(Map<String, Object> configValue, String validationRule) {
        if (configValue == null || configValue.isEmpty()) {
            throw BusinessException.validationError("配置值不能为空");
        }
        if (validationRule != null && !validationRule.isEmpty()) {
            try {
                Map<String, Object> rule = JsonUtils.fromJson(validationRule, Map.class);
                if (rule != null) {
                    for (Map.Entry<String, Object> entry : rule.entrySet()) {
                        Object value = configValue.get(entry.getKey());
                        if (value == null && Boolean.TRUE.equals(rule.get("required"))) {
                            throw BusinessException.validationError("缺少必填字段: " + entry.getKey());
                        }
                    }
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Validation rule parse failed: {}", validationRule);
            }
        }
    }

    private void publishConfigChangeEvent(DynamicConfig config, Map<String, Object> oldValue, Map<String, Object> newValue) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("configId", config.getConfigId());
            eventData.put("configKey", config.getConfigKey());
            eventData.put("oldValue", oldValue);
            eventData.put("newValue", newValue);
            eventData.put("timestamp", System.currentTimeMillis());
            eventData.put("hotReloadable", config.getHotReloadable());

            com.chaoslab.event.DomainEvent event = new com.chaoslab.event.DomainEvent(
                    "DYNAMIC_CONFIG_CHANGED",
                    eventData,
                    "system"
            );
            eventPublisher.publishEvent(event);
            log.debug("Published config change event for key: {}", config.getConfigKey());
        } catch (Exception e) {
            log.error("Failed to publish config change event", e);
        }
    }

    private void publishConfigAppliedEvent(SidecarInstance instance, ConfigTemplate template, SidecarConfig config) {
        try {
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("instanceId", instance.getInstanceId());
            eventData.put("templateId", template.getTemplateId());
            eventData.put("configId", config.getConfigId());
            eventData.put("version", config.getVersion());
            eventData.put("scenario", template.getScenario());
            eventData.put("timestamp", System.currentTimeMillis());

            com.chaoslab.event.DomainEvent event = new com.chaoslab.event.DomainEvent(
                    "CONFIG_TEMPLATE_APPLIED",
                    eventData,
                    "system"
            );
            eventPublisher.publishEvent(event);
            log.debug("Published config applied event for instance: {}", instance.getInstanceId());
        } catch (Exception e) {
            log.error("Failed to publish config applied event", e);
        }
    }

    private void invalidateTemplateCache(String scenario) {
        templateCache.remove(scenario);
    }

    public Mono<Map<String, Object>> getConfigStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalConfigs", dynamicConfigMapper.selectCount(null));
            stats.put("totalTemplates", configTemplateMapper.selectCount(null));
            stats.put("cachedConfigs", configCache.size());
            stats.put("cachedTemplateScenarios", templateCache.size());
            stats.put("hotReloadableConfigs", configCache.values().stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHotReloadable()))
                    .count());
            return stats;
        });
    }
}
