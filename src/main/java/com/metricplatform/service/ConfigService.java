package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.dto.ConfigDTO;
import com.metricplatform.entity.SysConfig;
import com.metricplatform.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService extends ServiceImpl<SysConfigMapper, SysConfig> {

    private final Map<String, Map<String, ConfigDTO.ConfigParameterValidator>> validatorRegistry = new ConcurrentHashMap<>();

    public void registerValidators(String namespace, Map<String, ConfigDTO.ConfigParameterValidator> validators) {
        validatorRegistry.put(namespace, validators);
        log.info("已注册配置校验器: namespace={}, parameters={}", namespace, validators.keySet());
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "configs", allEntries = true)
    public SysConfig createConfig(ConfigDTO dto) {
        SysConfig existing = this.getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getNamespace, dto.getNamespace())
                .eq(SysConfig::getEnabled, true));

        int version = 1;
        if (existing != null) {
            existing.setEnabled(false);
            this.updateById(existing);
            version = existing.getVersion() + 1;
        }

        Map<String, Object> validatedParams = validateAndApplyDefaults(dto.getNamespace(), dto.getParameters(), dto.getValidators());

        SysConfig config = new SysConfig();
        config.setConfigId("cfg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        config.setNamespace(dto.getNamespace());
        config.setVersion(version);
        config.setParameters(validatedParams);
        config.setEnabled(dto.getEnabled());
        config.setAppliedAt(dto.getEnabled() ? LocalDateTime.now() : null);

        this.save(config);
        log.info("已创建配置: namespace={}, version={}", dto.getNamespace(), version);
        return config;
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "configs", allEntries = true)
    public SysConfig updateConfig(String configId, ConfigDTO dto) {
        SysConfig config = this.getById(configId);
        if (config == null) {
            throw new IllegalArgumentException("配置不存在: " + configId);
        }

        Map<String, Object> validatedParams = validateAndApplyDefaults(dto.getNamespace(), dto.getParameters(), dto.getValidators());

        config.setParameters(validatedParams);
        config.setEnabled(dto.getEnabled());
        config.setAppliedAt(dto.getEnabled() ? LocalDateTime.now() : null);

        this.updateById(config);
        log.info("已更新配置: {}", configId);
        return config;
    }

    @Cacheable(value = "configs", key = "#namespace")
    public SysConfig getActiveConfig(String namespace) {
        return this.getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getNamespace, namespace)
                .eq(SysConfig::getEnabled, true)
                .orderByDesc(SysConfig::getVersion)
                .last("LIMIT 1"));
    }

    @Cacheable(value = "configs", key = "#namespace + ':' + #version")
    public SysConfig getConfigByVersion(String namespace, int version) {
        return this.getOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getNamespace, namespace)
                .eq(SysConfig::getVersion, version));
    }

    public Object getParameter(String namespace, String key) {
        SysConfig config = getActiveConfig(namespace);
        if (config != null && config.getParameters() != null) {
            return config.getParameters().get(key);
        }
        Map<String, ConfigDTO.ConfigParameterValidator> validators = validatorRegistry.get(namespace);
        if (validators != null && validators.containsKey(key)) {
            return validators.get(key).getDefaultValue();
        }
        return null;
    }

    public <T> T getParameter(String namespace, String key, Class<T> type, T defaultValue) {
        Object value = getParameter(namespace, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return convertValue(value, type);
        } catch (Exception e) {
            log.warn("配置参数类型转换失败: {}.{}", namespace, key, e);
            return defaultValue;
        }
    }

    private Map<String, Object> validateAndApplyDefaults(String namespace,
                                                        Map<String, Object> parameters,
                                                        Map<String, ConfigDTO.ConfigParameterValidator> validators) {
        Map<String, Object> result = new HashMap<>(parameters);
        Map<String, ConfigDTO.ConfigParameterValidator> registeredValidators = validatorRegistry.get(namespace);

        Map<String, ConfigDTO.ConfigParameterValidator> allValidators = new ConcurrentHashMap<>();
        if (registeredValidators != null) {
            allValidators.putAll(registeredValidators);
        }
        if (validators != null) {
            allValidators.putAll(validators);
        }

        for (Map.Entry<String, ConfigDTO.ConfigParameterValidator> entry : allValidators.entrySet()) {
            String key = entry.getKey();
            ConfigDTO.ConfigParameterValidator validator = entry.getValue();
            Object value = result.get(key);

            if (value == null) {
                if (validator.isRequired() && validator.getDefaultValue() == null) {
                    throw new IllegalArgumentException("必填参数缺失: " + key);
                }
                if (validator.getDefaultValue() != null) {
                    result.put(key, validator.getDefaultValue());
                    log.debug("配置参数使用默认值: {} = {}", key, validator.getDefaultValue());
                }
                continue;
            }

            validateParameter(key, value, validator);
        }

        return result;
    }

    private void validateParameter(String key, Object value, ConfigDTO.ConfigParameterValidator validator) {
        if (validator.getType() != null && !validator.getType().isEmpty()) {
            boolean typeValid = switch (validator.getType().toLowerCase()) {
                case "string" -> value instanceof String;
                case "number", "integer" -> value instanceof Number;
                case "boolean" -> value instanceof Boolean;
                case "array", "list" -> value instanceof Collection || value.getClass().isArray();
                case "object", "map" -> value instanceof Map;
                default -> true;
            };
            if (!typeValid) {
                throw new IllegalArgumentException(String.format(
                        "参数类型错误: %s, 期望: %s, 实际: %s",
                        key, validator.getType(), value.getClass().getSimpleName()));
            }
        }

        if (validator.getPattern() != null && value instanceof String str) {
            if (!Pattern.matches(validator.getPattern(), str)) {
                throw new IllegalArgumentException(
                        "参数格式错误: " + key + ", 不匹配模式: " + validator.getPattern());
            }
        }

        if (value instanceof Number num) {
            if (validator.getMin() != null && num.longValue() < validator.getMin()) {
                throw new IllegalArgumentException(
                        "参数值过小: " + key + ", 最小值: " + validator.getMin());
            }
            if (validator.getMax() != null && num.longValue() > validator.getMax()) {
                throw new IllegalArgumentException(
                        "参数值过大: " + key + ", 最大值: " + validator.getMax());
            }
        }

        if (validator.getAllowedValues() != null && validator.getAllowedValues().length > 0) {
            boolean allowed = false;
            for (String allowedValue : validator.getAllowedValues()) {
                if (allowedValue.equals(String.valueOf(value))) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new IllegalArgumentException(
                        "参数值不在允许范围内: " + key + ", 允许值: " + Arrays.toString(validator.getAllowedValues()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T convertValue(Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return (T) value;
        }
        if (type == String.class) {
            return (T) String.valueOf(value);
        }
        if (type == Integer.class || type == int.class) {
            return (T) Integer.valueOf(((Number) value).intValue());
        }
        if (type == Long.class || type == long.class) {
            return (T) Long.valueOf(((Number) value).longValue());
        }
        if (type == Double.class || type == double.class) {
            return (T) Double.valueOf(((Number) value).doubleValue());
        }
        if (type == Boolean.class || type == boolean.class) {
            if (value instanceof Boolean b) {
                return (T) b;
            }
            return (T) Boolean.valueOf(String.valueOf(value));
        }
        throw new IllegalArgumentException("不支持的类型转换: " + type.getName());
    }

    public List<SysConfig> getAllConfigs(String namespace) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        if (namespace != null && !namespace.isEmpty()) {
            wrapper.eq(SysConfig::getNamespace, namespace);
        }
        wrapper.orderByDesc(SysConfig::getNamespace, SysConfig::getVersion);
        return this.list(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "configs", allEntries = true)
    public SysConfig applyConfig(String configId) {
        SysConfig config = this.getById(configId);
        if (config == null) {
            throw new IllegalArgumentException("配置不存在: " + configId);
        }

        this.list(new LambdaQueryWrapper<SysConfig>()
                        .eq(SysConfig::getNamespace, config.getNamespace())
                        .eq(SysConfig::getEnabled, true))
                .forEach(c -> {
                    c.setEnabled(false);
                    this.updateById(c);
                });

        config.setEnabled(true);
        config.setAppliedAt(LocalDateTime.now());
        this.updateById(config);

        log.info("已应用配置: {} (namespace: {}, version: {})",
                configId, config.getNamespace(), config.getVersion());
        return config;
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "configs", allEntries = true)
    public boolean deleteConfig(String configId) {
        return this.removeById(configId);
    }

    public Set<String> getNamespaces() {
        return new HashSet<>(this.list().stream()
                .map(SysConfig::getNamespace)
                .distinct()
                .toList());
    }
}
