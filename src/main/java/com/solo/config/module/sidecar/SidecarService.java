package com.solo.config.module.sidecar;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.solo.config.common.IdGenerator;
import com.solo.config.common.exception.BusinessException;
import com.solo.config.entity.SidecarInstance;
import com.solo.config.mapper.SidecarInstanceMapper;
import com.solo.config.module.sidecar.event.SidecarEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarService {

    private final SidecarInstanceMapper sidecarInstanceMapper;
    private final SidecarProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public Mono<SidecarInstance> injectSidecar(String podName, String namespace, Map<String, Object> config) {
        return Mono.fromCallable(() -> {
            SidecarInstance existing = sidecarInstanceMapper.selectOne(
                    new QueryWrapper<SidecarInstance>()
                            .eq("pod_name", podName)
                            .eq("namespace", namespace)
            );

            if (existing != null) {
                log.warn("Sidecar already exists for pod: {}/{}", namespace, podName);
                return existing;
            }

            SidecarInstance instance = new SidecarInstance();
            instance.setInstanceId(IdGenerator.generateInstanceId());
            instance.setPodName(podName);
            instance.setNamespace(namespace);
            instance.setStatus("injecting");
            instance.setCpuLimit(properties.getResources().getCpu());
            instance.setMemoryLimit(properties.getResources().getMemory());
            instance.setConfigVersion(1);
            instance.setLastHeartbeat(LocalDateTime.now());

            sidecarInstanceMapper.insert(instance);
            log.info("Sidecar injected for pod: {}/{}, instanceId: {}", namespace, podName, instance.getInstanceId());

            instance.setStatus("running");
            sidecarInstanceMapper.updateById(instance);

            eventPublisher.publishEvent(SidecarEvent.injected(this, instance.getInstanceId(), podName, namespace));

            return instance;
        })
        .timeout(Duration.ofMillis(properties.getTimeout().getInjectTimeoutMs()))
        .onErrorResume(e -> {
            log.error("Sidecar injection timed out or failed for pod: {}/{}", namespace, podName, e);
            return Mono.error(new BusinessException("Sidecar injection failed: " + e.getMessage()));
        });
    }

    public Mono<SidecarInstance> updateConfig(String instanceId, Map<String, Object> newConfig) {
        return Mono.fromCallable(() -> {
            SidecarInstance instance = sidecarInstanceMapper.selectOne(
                    new QueryWrapper<SidecarInstance>()
                            .eq("instance_id", instanceId)
            );

            if (instance == null) {
                throw new BusinessException("Sidecar instance not found: " + instanceId);
            }

            int oldVersion = instance.getConfigVersion();
            instance.setConfigVersion(oldVersion + 1);
            instance.setStatus("updating");
            instance.setLastHeartbeat(LocalDateTime.now());
            sidecarInstanceMapper.updateById(instance);

            log.info("Sidecar config updating, instanceId: {}, newVersion: {}", instanceId, instance.getConfigVersion());

            instance.setStatus("running");
            sidecarInstanceMapper.updateById(instance);

            eventPublisher.publishEvent(SidecarEvent.configUpdated(
                    this, instanceId, instance.getPodName(), instance.getNamespace(),
                    oldVersion, instance.getConfigVersion()));

            return instance;
        })
        .timeout(Duration.ofMillis(properties.getTimeout().getUpdateTimeoutMs()))
        .onErrorResume(e -> {
            log.error("Sidecar config update timed out or failed for instance: {}", instanceId, e);
            return Mono.error(new BusinessException("Sidecar config update failed: " + e.getMessage()));
        });
    }

    public Mono<Void> removeSidecar(String instanceId) {
        return Mono.fromRunnable(() -> {
            SidecarInstance instance = sidecarInstanceMapper.selectOne(
                    new QueryWrapper<SidecarInstance>()
                            .eq("instance_id", instanceId)
            );

            if (instance != null) {
                instance.setStatus("terminating");
                sidecarInstanceMapper.updateById(instance);
                sidecarInstanceMapper.deleteById(instance.getId());
                log.info("Sidecar removed, instanceId: {}", instanceId);

                eventPublisher.publishEvent(SidecarEvent.removed(
                        this, instanceId, instance.getPodName(), instance.getNamespace()));
            }
        })
        .timeout(Duration.ofMillis(properties.getTimeout().getRemoveTimeoutMs()))
        .onErrorResume(e -> {
            log.error("Sidecar removal timed out or failed for instance: {}", instanceId, e);
            return Mono.error(new BusinessException("Sidecar removal failed: " + e.getMessage()));
        });
    }

    public Mono<Void> heartbeat(String instanceId) {
        return Mono.fromRunnable(() -> {
            SidecarInstance instance = sidecarInstanceMapper.selectOne(
                    new QueryWrapper<SidecarInstance>()
                            .eq("instance_id", instanceId)
            );

            if (instance != null) {
                instance.setLastHeartbeat(LocalDateTime.now());
                sidecarInstanceMapper.updateById(instance);
            }
        })
        .timeout(Duration.ofMillis(properties.getTimeout().getHeartbeatTimeoutMs()))
        .onErrorResume(e -> {
            log.error("Sidecar heartbeat timed out or failed for instance: {}", instanceId, e);
            return Mono.error(new BusinessException("Sidecar heartbeat failed: " + e.getMessage()));
        });
    }

    public Flux<SidecarInstance> listInstances(String namespace) {
        return Flux.fromIterable(
                sidecarInstanceMapper.selectList(
                        new QueryWrapper<SidecarInstance>()
                                .eq(namespace != null, "namespace", namespace)
                                .orderByDesc("created_at")
                )
        );
    }

    public Mono<SidecarInstance> getInstance(String instanceId) {
        return Mono.justOrEmpty(
                sidecarInstanceMapper.selectOne(
                        new QueryWrapper<SidecarInstance>()
                                .eq("instance_id", instanceId)
                )
        );
    }

    @Scheduled(fixedRate = 30000)
    public void checkHealthy() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(60);
        var unhealthyInstances = sidecarInstanceMapper.selectList(
                new QueryWrapper<SidecarInstance>()
                        .lt("last_heartbeat", threshold)
                        .eq("status", "running")
        );

        for (SidecarInstance instance : unhealthyInstances) {
            instance.setStatus("unhealthy");
            sidecarInstanceMapper.updateById(instance);
            log.warn("Sidecar instance marked unhealthy: {}", instance.getInstanceId());

            eventPublisher.publishEvent(SidecarEvent.unhealthy(
                    this, instance.getInstanceId(), instance.getPodName(), instance.getNamespace()));
        }

        var recoveringInstances = sidecarInstanceMapper.selectList(
                new QueryWrapper<SidecarInstance>()
                        .ge("last_heartbeat", threshold)
                        .eq("status", "unhealthy")
        );

        for (SidecarInstance instance : recoveringInstances) {
            instance.setStatus("running");
            sidecarInstanceMapper.updateById(instance);
            log.info("Sidecar instance recovered: {}", instance.getInstanceId());

            eventPublisher.publishEvent(SidecarEvent.healthy(
                    this, instance.getInstanceId(), instance.getPodName(), instance.getNamespace()));
        }
    }

    @Scheduled(fixedRateString = "${sidecar.hot-reload.interval:10000}")
    public void hotReloadConfigs() {
        if (!properties.getHotReload().isEnabled()) {
            return;
        }
        log.debug("Sidecar hot reload check triggered");
    }
}
