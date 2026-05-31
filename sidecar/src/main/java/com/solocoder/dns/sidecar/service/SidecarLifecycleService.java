package com.solocoder.dns.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.dns.common.exception.ResourceNotFoundException;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.sidecar.model.SidecarInjectionPolicy;
import com.solocoder.dns.sidecar.model.SidecarInstance;
import com.solocoder.dns.persistence.entity.SidecarInstancePO;
import com.solocoder.dns.persistence.mapper.SidecarInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarLifecycleService {
    private final SidecarInstanceMapper sidecarMapper;
    private final Map<String, SidecarInjectionPolicy> policyStore = new ConcurrentHashMap<>();

    public SidecarInstance registerInstance(SidecarInstance instance) {
        instance.setInstanceId(IdGenerator.generateId("sidecar"));
        instance.setCreatedAt(LocalDateTime.now());
        instance.setHeartbeatAt(LocalDateTime.now());
        instance.setStatus("RUNNING");
        sidecarMapper.insert(toPO(instance));
        log.info("Sidecar instance registered: {}", instance.getInstanceId());
        return instance;
    }

    public void heartbeat(String instanceId) {
        SidecarInstancePO po = sidecarMapper.selectById(instanceId);
        if (po == null) {
            throw new ResourceNotFoundException("SidecarInstance", instanceId);
        }
        po.setHeartbeatAt(LocalDateTime.now());
        po.setStatus("RUNNING");
        sidecarMapper.updateById(po);
    }

    public SidecarInstance getInstance(String instanceId) {
        SidecarInstancePO po = sidecarMapper.selectById(instanceId);
        if (po == null) {
            throw new ResourceNotFoundException("SidecarInstance", instanceId);
        }
        return toDomain(po);
    }

    public List<SidecarInstance> listInstances(String serviceName) {
        LambdaQueryWrapper<SidecarInstancePO> wrapper = new LambdaQueryWrapper<>();
        if (serviceName != null && !serviceName.isEmpty()) {
            wrapper.eq(SidecarInstancePO::getServiceName, serviceName);
        }
        return sidecarMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public void updateConfig(String instanceId, String configHash) {
        SidecarInstancePO po = sidecarMapper.selectById(instanceId);
        if (po == null) {
            throw new ResourceNotFoundException("SidecarInstance", instanceId);
        }
        po.setConfigHash(configHash);
        sidecarMapper.updateById(po);
        log.info("Sidecar config updated: {} -> {}", instanceId, configHash);
    }

    public void updateResourceLimits(String instanceId, Double cpuLimit, Double memoryLimit) {
        SidecarInstancePO po = sidecarMapper.selectById(instanceId);
        if (po == null) {
            throw new ResourceNotFoundException("SidecarInstance", instanceId);
        }
        po.setCpuLimit(cpuLimit);
        po.setMemoryLimit(memoryLimit);
        sidecarMapper.updateById(po);
        log.info("Sidecar resource limits updated: {}", instanceId);
    }

    public void deregisterInstance(String instanceId) {
        SidecarInstancePO po = sidecarMapper.selectById(instanceId);
        if (po != null) {
            po.setStatus("STOPPED");
            sidecarMapper.updateById(po);
            log.info("Sidecar instance deregistered: {}", instanceId);
        }
    }

    public SidecarInjectionPolicy createInjectionPolicy(SidecarInjectionPolicy policy) {
        policy.setPolicyId(IdGenerator.generateId("policy"));
        policyStore.put(policy.getPolicyId(), policy);
        log.info("Sidecar injection policy created: {}", policy.getPolicyId());
        return policy;
    }

    public void hotReloadConfig(String instanceId) {
        log.info("Triggering config hot reload for sidecar: {}", instanceId);
    }

    private SidecarInstancePO toPO(SidecarInstance instance) {
        SidecarInstancePO po = new SidecarInstancePO();
        po.setInstanceId(instance.getInstanceId());
        po.setServiceName(instance.getServiceName());
        po.setVersion(instance.getVersion());
        po.setHost(instance.getHost());
        po.setPort(instance.getPort());
        po.setStatus(instance.getStatus());
        po.setConfigHash(instance.getConfigHash());
        po.setCpuLimit(instance.getCpuLimit());
        po.setMemoryLimit(instance.getMemoryLimit());
        po.setCreatedAt(instance.getCreatedAt());
        po.setHeartbeatAt(instance.getHeartbeatAt());
        return po;
    }

    private SidecarInstance toDomain(SidecarInstancePO po) {
        SidecarInstance instance = new SidecarInstance();
        instance.setInstanceId(po.getInstanceId());
        instance.setServiceName(po.getServiceName());
        instance.setVersion(po.getVersion());
        instance.setHost(po.getHost());
        instance.setPort(po.getPort());
        instance.setStatus(po.getStatus());
        instance.setConfigHash(po.getConfigHash());
        instance.setCpuLimit(po.getCpuLimit());
        instance.setMemoryLimit(po.getMemoryLimit());
        instance.setCreatedAt(po.getCreatedAt());
        instance.setHeartbeatAt(po.getHeartbeatAt());
        return instance;
    }
}
