package com.chaoslab.modules.sidecar.listener;

import com.chaoslab.entity.SidecarInstance;
import com.chaoslab.event.DomainEvent;
import com.chaoslab.mapper.SidecarInstanceMapper;
import com.chaoslab.modules.sidecar.service.SidecarLifecycleService;
import com.chaoslab.modules.sidecar.service.SidecarDynamicConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SidecarConfigChangeListener {

    private final SidecarInstanceMapper sidecarInstanceMapper;
    private final SidecarLifecycleService sidecarLifecycleService;
    private final SidecarDynamicConfigService dynamicConfigService;

    @Async
    @EventListener(condition = "#event.type == 'DYNAMIC_CONFIG_CHANGED'")
    public void onDynamicConfigChanged(DomainEvent event) {
        log.info("Received dynamic config change event: {}", event.getPayload());

        try {
            Map<String, Object> payload = (Map<String, Object>) event.getPayload();
            Boolean hotReloadable = (Boolean) payload.get("hotReloadable");
            if (!Boolean.TRUE.equals(hotReloadable)) {
                log.info("Config is not hot reloadable, skipping live update");
                return;
            }

            String configKey = (String) payload.get("configKey");
            if (configKey != null && configKey.startsWith("sidecar.")) {
                propagateConfigChangeToInstances(configKey, payload);
            }

        } catch (Exception e) {
            log.error("Failed to process dynamic config change event", e);
        }
    }

    @Async
    @EventListener(condition = "#event.type == 'CONFIG_TEMPLATE_APPLIED'")
    public void onConfigTemplateApplied(DomainEvent event) {
        log.info("Received config template applied event: {}", event.getPayload());

        try {
            Map<String, Object> payload = (Map<String, Object>) event.getPayload();
            String instanceId = (String) payload.get("instanceId");
            String configId = (String) payload.get("configId");

            notifySidecarInstance(instanceId, configId, "CONFIG_UPDATED");

        } catch (Exception e) {
            log.error("Failed to process config template applied event", e);
        }
    }

    private void propagateConfigChangeToInstances(String configKey, Map<String, Object> payload) {
        try {
            Map<String, Object> newValue = (Map<String, Object>) payload.get("newValue");
            if (newValue == null) {
                return;
            }

            LambdaQueryWrapper<SidecarInstance> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarInstance::getStatus, "running");
            List<SidecarInstance> instances = sidecarInstanceMapper.selectList(wrapper);

            log.info("Propagating config change to {} running instances", instances.size());

            Flux.fromIterable(instances)
                    .flatMap(instance -> updateInstanceConfig(instance, configKey, newValue)
                            .subscribeOn(Schedulers.boundedElastic()), 10)
                    .doOnComplete(() -> log.info("Config change propagation completed for key: {}", configKey))
                    .doOnError(e -> log.error("Config change propagation failed", e))
                    .subscribe();

        } catch (Exception e) {
            log.error("Failed to propagate config change to instances", e);
        }
    }

    private Flux<Void> updateInstanceConfig(SidecarInstance instance, String configKey, Map<String, Object> newValue) {
        return dynamicConfigService.getEffectiveConfig(instance.getInstanceId())
                .flatMap(effectiveConfig -> {
                    log.debug("Updating instance {} config for key: {}", instance.getInstanceId(), configKey);

                    instance.setLastHeartbeat(LocalDateTime.now());
                    instance.setConfigUpdatePending(true);
                    sidecarInstanceMapper.updateById(instance);

                    return Flux.empty();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to update config for instance: {}", instance.getInstanceId(), e);
                    return Flux.empty();
                });
    }

    private void notifySidecarInstance(String instanceId, String configId, String action) {
        log.info("Notifying instance {}: {} with config {}", instanceId, action, configId);

        try {
            LambdaQueryWrapper<SidecarInstance> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SidecarInstance::getInstanceId, instanceId);
            SidecarInstance instance = sidecarInstanceMapper.selectOne(wrapper);
            if (instance != null) {
                instance.setLastHeartbeat(LocalDateTime.now());
                sidecarInstanceMapper.updateById(instance);
            }
        } catch (Exception e) {
            log.error("Failed to notify sidecar instance: {}", instanceId, e);
        }
    }
}
