package com.chaoslab.modules.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chaoslab.common.JsonUtils;
import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.entity.SidecarConfig;
import com.chaoslab.entity.SidecarInjectionPolicy;
import com.chaoslab.entity.SidecarInstance;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.SidecarConfigMapper;
import com.chaoslab.mapper.SidecarInjectionPolicyMapper;
import com.chaoslab.mapper.SidecarInstanceMapper;
import com.chaoslab.modules.sidecar.dto.ConfigUpdateRequest;
import com.chaoslab.modules.sidecar.dto.InjectionPolicyCreateRequest;
import com.chaoslab.modules.sidecar.dto.ResourceLimitUpdateRequest;
import com.chaoslab.modules.sidecar.dto.SidecarInstanceStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarLifecycleService {

    private final SidecarInjectionPolicyMapper policyMapper;
    private final SidecarInstanceMapper instanceMapper;
    private final SidecarConfigMapper configMapper;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<SidecarInjectionPolicy> createInjectionPolicy(InjectionPolicyCreateRequest request) {
        return Mono.fromCallable(() -> {
            validatePolicyRequest(request);

            SidecarInjectionPolicy policy = new SidecarInjectionPolicy();
            policy.setPolicyId("pol-" + UUID.randomUUID().toString().substring(0, 8));
            policy.setName(request.getName());
            policy.setNamespace(request.getNamespace());
            policy.setSelector(request.getSelector());
            policy.setSidecarImage(request.getSidecarImage());
            policy.setResources(request.getResources() != null ? request.getResources() : defaultResources());
            policy.setInjectionMode(request.getInjectionMode());
            policy.setEnabled(request.getEnabled());

            policyMapper.insert(policy);
            log.info("Created injection policy: {}", policy.getPolicyId());
            return policy;
        });
    }

    public Mono<Page<SidecarInjectionPolicy>> listPolicies(String namespace, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInjectionPolicy> wrapper = new LambdaQueryWrapper<>();
            if (namespace != null && !namespace.isEmpty()) {
                wrapper.eq(SidecarInjectionPolicy::getNamespace, namespace);
            }
            wrapper.orderByDesc(SidecarInjectionPolicy::getCreatedAt);
            return policyMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<SidecarInjectionPolicy> getPolicy(String policyId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInjectionPolicy> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarInjectionPolicy::getPolicyId, policyId);
            SidecarInjectionPolicy policy = policyMapper.selectOne(wrapper);
            if (policy == null) {
                throw BusinessException.notFound("注入策略不存在: " + policyId);
            }
            return policy;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<SidecarInstance> injectSidecar(String policyId, String targetPod, String namespace) {
        return Mono.fromCallable(() -> {
            SidecarInjectionPolicy policy = policyMapper.selectOne(
                    new LambdaQueryWrapper<SidecarInjectionPolicy>()
                            .eq(SidecarInjectionPolicy::getPolicyId, policyId)
                            .eq(SidecarInjectionPolicy::getEnabled, true));
            if (policy == null) {
                throw BusinessException.notFound("注入策略不存在或未启用: " + policyId);
            }

            SidecarInstance instance = new SidecarInstance();
            instance.setInstanceId("si-" + UUID.randomUUID().toString().substring(0, 8));
            instance.setPolicyId(policyId);
            instance.setTargetPod(targetPod);
            instance.setNamespace(namespace);
            instance.setStatus("injecting");
            instance.setLastHeartbeat(LocalDateTime.now());

            instanceMapper.insert(instance);

            SidecarConfig config = new SidecarConfig();
            config.setConfigId("sc-" + UUID.randomUUID().toString().substring(0, 8));
            config.setInstanceId(instance.getInstanceId());
            config.setConfigData(Map.of(
                    "image", policy.getSidecarImage(),
                    "resources", policy.getResources()
            ));
            config.setVersion(1);
            config.setApplied(false);
            config.setAppliedAt(null);

            configMapper.insert(config);

            log.info("Injected sidecar instance: {} for pod: {}", instance.getInstanceId(), targetPod);
            return instance;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<SidecarConfig> updateConfig(ConfigUpdateRequest request) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInstance> instanceWrapper = new LambdaQueryWrapper<>();
            instanceWrapper.eq(SidecarInstance::getInstanceId, request.getInstanceId());
            SidecarInstance instance = instanceMapper.selectOne(instanceWrapper);
            if (instance == null) {
                throw BusinessException.notFound("Sidecar实例不存在: " + request.getInstanceId());
            }

            LambdaQueryWrapper<SidecarConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(SidecarConfig::getInstanceId, request.getInstanceId())
                    .orderByDesc(SidecarConfig::getVersion)
                    .last("LIMIT 1");
            SidecarConfig latestConfig = configMapper.selectOne(configWrapper);

            int newVersion = latestConfig != null ? latestConfig.getVersion() + 1 : 1;
            String configHash = calculateConfigHash(request.getConfigData());

            SidecarConfig newConfig = new SidecarConfig();
            newConfig.setConfigId("sc-" + UUID.randomUUID().toString().substring(0, 8));
            newConfig.setInstanceId(request.getInstanceId());
            newConfig.setConfigData(request.getConfigData());
            newConfig.setVersion(newVersion);
            newConfig.setApplied(false);
            newConfig.setAppliedAt(null);

            configMapper.insert(newConfig);

            instance.setConfigHash(configHash);
            instance.setStatus("config_pending");
            instanceMapper.updateById(instance);

            log.info("Updated sidecar config: {} for instance: {} version: {}",
                    newConfig.getConfigId(), request.getInstanceId(), newVersion);
            return newConfig;
        });
    }

    public Mono<SidecarConfig> getAppliedConfig(String instanceId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarConfig::getInstanceId, instanceId)
                    .eq(SidecarConfig::getApplied, true)
                    .orderByDesc(SidecarConfig::getVersion)
                    .last("LIMIT 1");
            SidecarConfig config = configMapper.selectOne(wrapper);
            if (config == null) {
                throw BusinessException.notFound("实例没有已应用的配置: " + instanceId);
            }
            return config;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<SidecarInstanceStatusResponse> updateResourceLimits(ResourceLimitUpdateRequest request) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInstance> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarInstance::getInstanceId, request.getInstanceId());
            SidecarInstance instance = instanceMapper.selectOne(wrapper);
            if (instance == null) {
                throw BusinessException.notFound("Sidecar实例不存在: " + request.getInstanceId());
            }

            LambdaQueryWrapper<SidecarConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(SidecarConfig::getInstanceId, request.getInstanceId())
                    .orderByDesc(SidecarConfig::getVersion)
                    .last("LIMIT 1");
            SidecarConfig latestConfig = configMapper.selectOne(configWrapper);

            Map<String, Object> currentConfig = latestConfig != null ?
                    new HashMap<>(latestConfig.getConfigData()) : new HashMap<>();
            Map<String, Object> resources = (Map<String, Object>) currentConfig.getOrDefault("resources", new HashMap<>());

            if (request.getCpuLimit() != null) {
                resources.put("cpuLimit", request.getCpuLimit());
            }
            if (request.getMemoryLimit() != null) {
                resources.put("memoryLimit", request.getMemoryLimit());
            }
            if (request.getCpuRequest() != null) {
                resources.put("cpuRequest", request.getCpuRequest());
            }
            if (request.getMemoryRequest() != null) {
                resources.put("memoryRequest", request.getMemoryRequest());
            }
            currentConfig.put("resources", resources);

            int newVersion = latestConfig != null ? latestConfig.getVersion() + 1 : 1;
            SidecarConfig newConfig = new SidecarConfig();
            newConfig.setConfigId("sc-" + UUID.randomUUID().toString().substring(0, 8));
            newConfig.setInstanceId(request.getInstanceId());
            newConfig.setConfigData(currentConfig);
            newConfig.setVersion(newVersion);
            newConfig.setApplied(false);

            configMapper.insert(newConfig);

            instance.setStatus("resource_update_pending");
            instanceMapper.updateById(instance);

            SidecarInstanceStatusResponse response = new SidecarInstanceStatusResponse();
            BeanUtils.copyProperties(instance, response);
            response.setResources(resources);

            log.info("Updated resource limits for instance: {}", request.getInstanceId());
            return response;
        });
    }

    public Mono<SidecarInstanceStatusResponse> getInstanceStatus(String instanceId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SidecarInstance> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarInstance::getInstanceId, instanceId);
            SidecarInstance instance = instanceMapper.selectOne(wrapper);
            if (instance == null) {
                throw BusinessException.notFound("Sidecar实例不存在: " + instanceId);
            }

            LambdaQueryWrapper<SidecarConfig> configWrapper = new LambdaQueryWrapper<>();
            configWrapper.eq(SidecarConfig::getInstanceId, instanceId)
                    .orderByDesc(SidecarConfig::getVersion)
                    .last("LIMIT 1");
            SidecarConfig latestConfig = configMapper.selectOne(configWrapper);

            SidecarInstanceStatusResponse response = new SidecarInstanceStatusResponse();
            BeanUtils.copyProperties(instance, response);
            if (latestConfig != null) {
                response.setResources((Map<String, Object>) latestConfig.getConfigData().get("resources"));
            }

            return response;
        });
    }

    @Transactional
    public Mono<Void> confirmConfigApplied(String instanceId, String configId) {
        return Mono.fromRunnable(() -> {
            LambdaQueryWrapper<SidecarConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarConfig::getConfigId, configId)
                    .eq(SidecarConfig::getInstanceId, instanceId);
            SidecarConfig config = configMapper.selectOne(wrapper);
            if (config == null) {
                throw BusinessException.notFound("配置不存在: " + configId);
            }

            config.setApplied(true);
            config.setAppliedAt(LocalDateTime.now());
            configMapper.updateById(config);

            LambdaQueryWrapper<SidecarInstance> instanceWrapper = new LambdaQueryWrapper<>();
            instanceWrapper.eq(SidecarInstance::getInstanceId, instanceId);
            SidecarInstance instance = instanceMapper.selectOne(instanceWrapper);
            instance.setStatus("running");
            instanceMapper.updateById(instance);

            log.info("Config applied confirmed: {} for instance: {}", configId, instanceId);
        });
    }

    private void validatePolicyRequest(InjectionPolicyCreateRequest request) {
        if (request.getSidecarImage() == null || request.getSidecarImage().isEmpty()) {
            throw BusinessException.validationError("Sidecar镜像不能为空");
        }
    }

    private Map<String, Object> defaultResources() {
        return Map.of(
                "cpuLimit", "500m",
                "memoryLimit", "256Mi",
                "cpuRequest", "100m",
                "memoryRequest", "128Mi"
        );
    }

    private String calculateConfigHash(Map<String, Object> configData) {
        try {
            String json = JsonUtils.toJson(configData);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString().substring(0, 32);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate config hash", e);
        }
    }
}
