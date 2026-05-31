package com.meshcontrol.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.sidecar.dto.*;
import com.meshcontrol.sidecar.entity.InjectionPolicy;
import com.meshcontrol.sidecar.entity.SidecarConfig;
import com.meshcontrol.sidecar.entity.SidecarInstance;
import com.meshcontrol.sidecar.mapper.InjectionPolicyMapper;
import com.meshcontrol.sidecar.mapper.SidecarConfigMapper;
import com.meshcontrol.sidecar.mapper.SidecarInstanceMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarLifecycleService extends BaseService<SidecarInstanceMapper, SidecarInstance> {

    private final SidecarInstanceMapper sidecarInstanceMapper;
    private final SidecarConfigMapper sidecarConfigMapper;
    private final InjectionPolicyMapper injectionPolicyMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, HttpURLConnection> activeConnections = new ConcurrentHashMap<>();
    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int MAX_PAYLOAD_SIZE = 1024 * 1024;

    @Transactional
    public SidecarInstance injectSidecar(SidecarInjectRequest request) {
        InjectionPolicy policy = injectionPolicyMapper.findByNamespace(request.getNamespace());
        if (policy != null && !policy.getEnabled()) {
            throw new BusinessException("Sidecar injection is disabled for namespace: " + request.getNamespace());
        }

        SidecarInstance existing = sidecarInstanceMapper.findByPodNameAndNamespace(
                request.getPodName(), request.getNamespace());
        if (existing != null) {
            throw new BusinessException("Sidecar already exists for pod: " + request.getPodName());
        }

        SidecarInstance sidecar = new SidecarInstance();
        sidecar.setSidecarId(IdGenerator.generateId("sc"));
        sidecar.setPodName(request.getPodName());
        sidecar.setNamespace(request.getNamespace());
        sidecar.setNodeName(request.getNodeName());
        sidecar.setServiceName(request.getServiceName());
        sidecar.setVersion(request.getVersion());
        sidecar.setStatus("injecting");
        sidecar.setConfigVersion(request.getConfigVersion());
        sidecar.setResources(request.getResources() != null ? request.getResources() : new HashMap<>());
        sidecar.setInjectedAt(LocalDateTime.now());
        sidecar.setLastHeartbeat(LocalDateTime.now());

        sidecarInstanceMapper.insert(sidecar);
        log.info("Sidecar injected: {} for pod: {}/{}",
                sidecar.getSidecarId(), request.getNamespace(), request.getPodName());

        return sidecar;
    }

    @Transactional
    public boolean updateSidecarStatus(String sidecarId, String status, Map<String, Object> resources) {
        SidecarInstance sidecar = sidecarInstanceMapper.selectById(sidecarId);
        if (sidecar == null) {
            throw new BusinessException("Sidecar not found: " + sidecarId);
        }

        sidecar.setStatus(status);
        if (resources != null) {
            sidecar.setResources(resources);
        }
        sidecar.setLastHeartbeat(LocalDateTime.now());

        return sidecarInstanceMapper.updateById(sidecar) > 0;
    }

    @Transactional
    public boolean updateSidecarConfig(String sidecarId, ConfigUpdateRequest request) {
        SidecarInstance sidecar = sidecarInstanceMapper.selectById(sidecarId);
        if (sidecar == null) {
            throw new BusinessException("Sidecar not found: " + sidecarId);
        }

        boolean success = pushConfigToSidecar(sidecar, request.getParameters());
        if (!success) {
            throw new BusinessException("Failed to push config to sidecar: " + sidecarId);
        }

        sidecar.setConfigVersion(sidecar.getConfigVersion() + 1);
        sidecar.setLastHeartbeat(LocalDateTime.now());
        sidecarInstanceMapper.updateById(sidecar);

        log.info("Sidecar config updated: {} to version {}", sidecarId, sidecar.getConfigVersion());
        return true;
    }

    private boolean pushConfigToSidecar(SidecarInstance sidecar, Map<String, Object> parameters) {
        validateSidecarInstance(sidecar);
        validateParameters(parameters);

        String endpoint = String.format("http://%s:15000/config/update", sidecar.getPodName());
        HttpURLConnection conn = null;
        OutputStream os = null;
        InputStream is = null;
        BufferedReader br = null;
        InputStream errorStream = null;

        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);

            String jsonPayload = objectMapper.writeValueAsString(Collections.singletonMap("config", parameters));
            byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            if (payloadBytes.length > MAX_PAYLOAD_SIZE) {
                throw new BusinessException("Payload size exceeds maximum allowed: " + MAX_PAYLOAD_SIZE + " bytes");
            }

            os = conn.getOutputStream();
            os.write(payloadBytes);
            os.flush();

            int responseCode = conn.getResponseCode();
            log.debug("Config push response for {}: {}", sidecar.getSidecarId(), responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                is = conn.getInputStream();
                br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String response = br.readLine();
                log.debug("Config push response body: {}", response);
                return true;
            } else {
                errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    br = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    log.warn("Config push failed for {} with status {}: {}",
                            sidecar.getSidecarId(), responseCode, errorResponse);
                }
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to push config to sidecar: {}", sidecar.getSidecarId(), e);
            return false;
        } finally {
            closeQuietly(br, sidecar.getSidecarId(), "BufferedReader");
            closeQuietly(is, sidecar.getSidecarId(), "InputStream");
            closeQuietly(errorStream, sidecar.getSidecarId(), "ErrorStream");
            closeQuietly(os, sidecar.getSidecarId(), "OutputStream");
            if (conn != null) {
                try {
                    conn.disconnect();
                    log.debug("Disconnected connection for sidecar: {}", sidecar.getSidecarId());
                } catch (Exception e) {
                    log.warn("Error disconnecting connection for sidecar: {}", sidecar.getSidecarId(), e);
                }
            }
        }
    }

    private void validateSidecarInstance(SidecarInstance sidecar) {
        if (sidecar == null) {
            throw new BusinessException("Sidecar instance cannot be null");
        }
        if (sidecar.getPodName() == null || sidecar.getPodName().isBlank()) {
            throw new BusinessException("Sidecar podName cannot be null or blank");
        }
        if (sidecar.getSidecarId() == null || sidecar.getSidecarId().isBlank()) {
            throw new BusinessException("Sidecar sidecarId cannot be null or blank");
        }
    }

    private void validateParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            throw new BusinessException("Config parameters cannot be null or empty");
        }
    }

    private void closeQuietly(AutoCloseable resource, String sidecarId, String resourceName) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Error closing {} for sidecar {}: {}", resourceName, sidecarId, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up all active connections during shutdown");
        for (Map.Entry<String, HttpURLConnection> entry : activeConnections.entrySet()) {
            try {
                entry.getValue().disconnect();
                log.debug("Disconnected connection: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("Error disconnecting connection {} during shutdown: {}", entry.getKey(), e.getMessage());
            }
        }
        activeConnections.clear();
    }

    @Transactional
    public boolean removeSidecar(String sidecarId) {
        SidecarInstance sidecar = sidecarInstanceMapper.selectById(sidecarId);
        if (sidecar == null) {
            throw new BusinessException("Sidecar not found: " + sidecarId);
        }

        String connKey = "conn_" + sidecarId;
        HttpURLConnection conn = activeConnections.remove(connKey);
        if (conn != null) {
            try {
                conn.disconnect();
                log.debug("Disconnected connection for sidecar: {}", sidecarId);
            } catch (Exception e) {
                log.warn("Error disconnecting connection for sidecar: {}", sidecarId, e);
            }
        }

        return sidecarInstanceMapper.deleteById(sidecarId) > 0;
    }

    public IPage<SidecarInstance> listSidecars(int pageNum, int pageSize, String namespace, String status) {
        LambdaQueryWrapper<SidecarInstance> wrapper = new LambdaQueryWrapper<>();
        if (namespace != null && !namespace.isBlank()) {
            wrapper.eq(SidecarInstance::getNamespace, namespace);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(SidecarInstance::getStatus, status);
        }
        wrapper.orderByDesc(SidecarInstance::getInjectedAt);
        return page(pageNum, pageSize, wrapper);
    }

    public SidecarInstance getSidecar(String sidecarId) {
        return sidecarInstanceMapper.selectById(sidecarId);
    }

    @Transactional
    public boolean heartbeat(String sidecarId, Map<String, Object> metrics) {
        SidecarInstance sidecar = sidecarInstanceMapper.selectById(sidecarId);
        if (sidecar == null) {
            throw new BusinessException("Sidecar not found: " + sidecarId);
        }

        sidecar.setLastHeartbeat(LocalDateTime.now());
        if (metrics != null) {
            Map<String, Object> existingResources = sidecar.getResources();
            if (existingResources != null) {
                existingResources.putAll(metrics);
            } else {
                sidecar.setResources(metrics);
            }
        }

        return sidecarInstanceMapper.updateById(sidecar) > 0;
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupStaleSidecars() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<SidecarInstance> staleSidecars = sidecarInstanceMapper.findStaleInstances(threshold);

        for (SidecarInstance sidecar : staleSidecars) {
            try {
                String connKey = "conn_" + sidecar.getSidecarId();
                HttpURLConnection conn = activeConnections.remove(connKey);
                if (conn != null) {
                    conn.disconnect();
                }

                sidecar.setStatus("unhealthy");
                sidecarInstanceMapper.updateById(sidecar);
                log.warn("Marked stale sidecar as unhealthy: {}", sidecar.getSidecarId());
            } catch (Exception e) {
                log.error("Error cleaning up stale sidecar: {}", sidecar.getSidecarId(), e);
            }
        }
    }

    @Transactional
    public InjectionPolicy createInjectionPolicy(InjectionPolicyRequest request) {
        InjectionPolicy policy = new InjectionPolicy();
        policy.setPolicyId(IdGenerator.generateId("ip"));
        policy.setNamespace(request.getNamespace());
        policy.setSelector(request.getSelector());
        policy.setInjectionMode(request.getInjectionMode());
        policy.setSidecarImage(request.getSidecarImage());
        policy.setResourceLimits(request.getResourceLimits());
        policy.setEnabled(request.getEnabled());
        policy.setCreatedAt(LocalDateTime.now());

        injectionPolicyMapper.insert(policy);
        log.info("Injection policy created: {} for namespace: {}", policy.getPolicyId(), policy.getNamespace());
        return policy;
    }

    @Transactional
    public boolean updateResourceLimits(String sidecarId, ResourceLimitUpdateRequest request) {
        SidecarInstance sidecar = sidecarInstanceMapper.selectById(sidecarId);
        if (sidecar == null) {
            throw new BusinessException("Sidecar not found: " + sidecarId);
        }

        Map<String, Object> resources = sidecar.getResources();
        if (resources == null) {
            resources = new HashMap<>();
        }
        resources.put("limits", request.getLimits());
        resources.put("requests", request.getRequests());

        sidecar.setResources(resources);
        return sidecarInstanceMapper.updateById(sidecar) > 0;
    }

    public List<SidecarConfig> getSidecarConfigs() {
        return sidecarConfigMapper.selectList(null);
    }
}
