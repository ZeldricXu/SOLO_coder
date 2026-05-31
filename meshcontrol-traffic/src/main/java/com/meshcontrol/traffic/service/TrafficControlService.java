package com.meshcontrol.traffic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.traffic.dto.*;
import com.meshcontrol.traffic.entity.CanaryRelease;
import com.meshcontrol.traffic.entity.TrafficPolicy;
import com.meshcontrol.traffic.mapper.CanaryReleaseMapper;
import com.meshcontrol.traffic.mapper.TrafficPolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficControlService extends BaseService<TrafficPolicyMapper, TrafficPolicy> {

    private final TrafficPolicyMapper trafficPolicyMapper;
    private final CanaryReleaseMapper canaryReleaseMapper;

    @Transactional
    public TrafficPolicy createPolicy(TrafficPolicyRequest request) {
        TrafficPolicy policy = new TrafficPolicy();
        policy.setPolicyId(IdGenerator.generateId("tp"));
        policy.setName(request.getName());
        policy.setType(request.getType());
        policy.setNamespace(request.getNamespace());
        policy.setServiceName(request.getServiceName());
        policy.setMatchRules(request.getMatchRules());
        policy.setRoutes(request.getRoutes());
        policy.setMirrorConfig(request.getMirrorConfig());
        policy.setCircuitBreaker(request.getCircuitBreaker());
        policy.setRetryPolicy(request.getRetryPolicy());
        policy.setTimeoutMs(request.getTimeoutMs());
        policy.setEnabled(request.getEnabled());
        policy.setPriority(request.getPriority());

        trafficPolicyMapper.insert(policy);
        log.info("Traffic policy created: {} type: {}", policy.getPolicyId(), policy.getType());
        return policy;
    }

    public IPage<TrafficPolicy> listPolicies(String type, String serviceName, String namespace,
                                             int pageNum, int pageSize) {
        LambdaQueryWrapper<TrafficPolicy> wrapper = new LambdaQueryWrapper<>();
        if (type != null) {
            wrapper.eq(TrafficPolicy::getType, type);
        }
        if (serviceName != null) {
            wrapper.eq(TrafficPolicy::getServiceName, serviceName);
        }
        if (namespace != null) {
            wrapper.eq(TrafficPolicy::getNamespace, namespace);
        }
        wrapper.orderByDesc(TrafficPolicy::getPriority, TrafficPolicy::getCreatedAt);
        return page(pageNum, pageSize, wrapper);
    }

    public TrafficPolicy getPolicy(String policyId) {
        return trafficPolicyMapper.selectById(policyId);
    }

    @Transactional
    public boolean updatePolicy(String policyId, TrafficPolicyRequest request) {
        TrafficPolicy policy = trafficPolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new BusinessException("Traffic policy not found");
        }

        policy.setName(request.getName());
        policy.setMatchRules(request.getMatchRules());
        policy.setRoutes(request.getRoutes());
        policy.setMirrorConfig(request.getMirrorConfig());
        policy.setCircuitBreaker(request.getCircuitBreaker());
        policy.setRetryPolicy(request.getRetryPolicy());
        policy.setTimeoutMs(request.getTimeoutMs());
        policy.setEnabled(request.getEnabled());
        policy.setPriority(request.getPriority());

        return trafficPolicyMapper.updateById(policy) > 0;
    }

    @Transactional
    public boolean deletePolicy(String policyId) {
        return trafficPolicyMapper.deleteById(policyId) > 0;
    }

    @Transactional
    public CanaryRelease startCanaryRelease(CanaryReleaseRequest request) {
        CanaryRelease release = new CanaryRelease();
        release.setReleaseId(IdGenerator.generateId("canary"));
        release.setName(request.getName());
        release.setServiceName(request.getServiceName());
        release.setNamespace(request.getNamespace());
        release.setPrimaryVersion(request.getPrimaryVersion());
        release.setCanaryVersion(request.getCanaryVersion());
        release.setTrafficSplit(request.getTrafficSplit() != null ? request.getTrafficSplit() : getDefaultTrafficSplit());
        release.setStrategy(request.getStrategy());
        release.setStatus("running");
        release.setStartedAt(LocalDateTime.now());

        canaryReleaseMapper.insert(release);
        createCanaryTrafficPolicy(release);
        log.info("Canary release started: {} for service: {}", release.getReleaseId(), release.getServiceName());
        return release;
    }

    @Transactional
    public boolean updateCanaryProgress(CanaryProgressRequest request) {
        CanaryRelease release = canaryReleaseMapper.selectById(request.getReleaseId());
        if (release == null) {
            throw new BusinessException("Canary release not found");
        }

        if (!"running".equals(release.getStatus())) {
            throw new BusinessException("Canary release is not running");
        }

        release.setTrafficSplit(request.getTrafficSplit());
        canaryReleaseMapper.updateById(release);
        updateCanaryTrafficPolicy(release);
        log.info("Canary release progress updated: {}", request.getReleaseId());
        return true;
    }

    @Transactional
    public boolean completeCanaryRelease(String releaseId) {
        CanaryRelease release = canaryReleaseMapper.selectById(releaseId);
        if (release == null) {
            throw new BusinessException("Canary release not found");
        }

        release.setStatus("completed");
        release.setCompletedAt(LocalDateTime.now());
        canaryReleaseMapper.updateById(release);
        removeCanaryTrafficPolicy(release);
        log.info("Canary release completed: {}", releaseId);
        return true;
    }

    @Transactional
    public boolean rollbackCanaryRelease(String releaseId) {
        CanaryRelease release = canaryReleaseMapper.selectById(releaseId);
        if (release == null) {
            throw new BusinessException("Canary release not found");
        }

        release.setStatus("rolled_back");
        release.setRollbackAt(LocalDateTime.now());
        canaryReleaseMapper.updateById(release);
        removeCanaryTrafficPolicy(release);
        log.info("Canary release rolled back: {}", releaseId);
        return true;
    }

    public List<CanaryRelease> listCanaryReleases(String serviceName, String status) {
        if (serviceName != null) {
            return canaryReleaseMapper.findByServiceName(serviceName);
        }
        if (status != null) {
            return canaryReleaseMapper.findByStatus(status);
        }
        return canaryReleaseMapper.selectList(null);
    }

    @Transactional
    public Map<String, Object> startBlueGreenDeployment(BlueGreenDeployRequest request) {
        TrafficPolicy policy = new TrafficPolicy();
        policy.setPolicyId(IdGenerator.generateId("bg"));
        policy.setName("blue-green-" + request.getServiceName());
        policy.setType("blue_green");
        policy.setNamespace(request.getNamespace());
        policy.setServiceName(request.getServiceName());
        policy.setRoutes(createBlueGreenRoutes(request));
        policy.setEnabled(true);
        policy.setPriority(100);

        trafficPolicyMapper.insert(policy);
        log.info("Blue-green deployment started for service: {}", request.getServiceName());

        Map<String, Object> result = new HashMap<>();
        result.put("policyId", policy.getPolicyId());
        result.put("status", "running");
        result.put("message", "Blue-green deployment initialized, all traffic to blue version");
        return result;
    }

    @Transactional
    public Map<String, Object> switchBlueGreenTraffic(String policyId, String targetVersion) {
        TrafficPolicy policy = trafficPolicyMapper.selectById(policyId);
        if (policy == null || !"blue_green".equals(policy.getType())) {
            throw new BusinessException("Invalid blue-green policy");
        }

        List<Map<String, Object>> routes = policy.getRoutes();
        for (Map<String, Object> route : routes) {
            route.put("weight", "green".equals(targetVersion) ? 100 : 0);
        }
        policy.setRoutes(routes);
        trafficPolicyMapper.updateById(policy);

        Map<String, Object> result = new HashMap<>();
        result.put("policyId", policyId);
        result.put("targetVersion", targetVersion);
        result.put("status", "switched");
        return result;
    }

    private Map<String, Object> getDefaultTrafficSplit() {
        Map<String, Object> split = new HashMap<>();
        split.put("primary", 90);
        split.put("canary", 10);
        return split;
    }

    private void createCanaryTrafficPolicy(CanaryRelease release) {
        TrafficPolicy policy = new TrafficPolicy();
        policy.setPolicyId(IdGenerator.generateId("tp"));
        policy.setName("canary-" + release.getServiceName());
        policy.setType("canary");
        policy.setNamespace(release.getNamespace());
        policy.setServiceName(release.getServiceName());
        policy.setRoutes(createCanaryRoutes(release));
        policy.setEnabled(true);
        policy.setPriority(50);
        trafficPolicyMapper.insert(policy);
    }

    private List<Map<String, Object>> createCanaryRoutes(CanaryRelease release) {
        Map<String, Object> primaryRoute = new HashMap<>();
        primaryRoute.put("version", release.getPrimaryVersion());
        primaryRoute.put("weight", release.getTrafficSplit().get("primary"));

        Map<String, Object> canaryRoute = new HashMap<>();
        canaryRoute.put("version", release.getCanaryVersion());
        canaryRoute.put("weight", release.getTrafficSplit().get("canary"));

        return List.of(primaryRoute, canaryRoute);
    }

    private void updateCanaryTrafficPolicy(CanaryRelease release) {
        List<TrafficPolicy> policies = trafficPolicyMapper.findByServiceName(release.getServiceName());
        for (TrafficPolicy policy : policies) {
            if ("canary".equals(policy.getType())) {
                policy.setRoutes(createCanaryRoutes(release));
                trafficPolicyMapper.updateById(policy);
            }
        }
    }

    private void removeCanaryTrafficPolicy(CanaryRelease release) {
        List<TrafficPolicy> policies = trafficPolicyMapper.findByServiceName(release.getServiceName());
        for (TrafficPolicy policy : policies) {
            if ("canary".equals(policy.getType()) && policy.getName().contains(release.getReleaseId())) {
                trafficPolicyMapper.deleteById(policy);
            }
        }
    }

    private List<Map<String, Object>> createBlueGreenRoutes(BlueGreenDeployRequest request) {
        Map<String, Object> blueRoute = new HashMap<>();
        blueRoute.put("version", request.getBlueVersion());
        blueRoute.put("weight", 100);

        Map<String, Object> greenRoute = new HashMap<>();
        greenRoute.put("version", request.getGreenVersion());
        greenRoute.put("weight", 0);

        return List.of(blueRoute, greenRoute);
    }

    public Map<String, Object> getEffectivePolicies(String serviceName) {
        List<TrafficPolicy> policies = trafficPolicyMapper.findByServiceName(serviceName);
        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", serviceName);
        result.put("policies", policies);
        return result;
    }
}
