package com.solo.config.module.sidecar.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
public class SidecarEvent extends ApplicationEvent {

    private final String eventType;
    private final String instanceId;
    private final String podName;
    private final String namespace;
    private final Map<String, Object> payload;
    private final LocalDateTime timestamp;

    public SidecarEvent(Object source, String eventType, String instanceId, String podName,
                        String namespace, Map<String, Object> payload) {
        super(source);
        this.eventType = eventType;
        this.instanceId = instanceId;
        this.podName = podName;
        this.namespace = namespace;
        this.payload = payload;
        this.timestamp = LocalDateTime.now();
    }

    public static SidecarEvent injected(Object source, String instanceId, String podName, String namespace) {
        return new SidecarEvent(source, "SIDECAR_INJECTED", instanceId, podName, namespace, null);
    }

    public static SidecarEvent configUpdated(Object source, String instanceId, String podName, String namespace,
                                              int oldVersion, int newVersion) {
        Map<String, Object> payload = Map.of("oldVersion", oldVersion, "newVersion", newVersion);
        return new SidecarEvent(source, "SIDECAR_CONFIG_UPDATED", instanceId, podName, namespace, payload);
    }

    public static SidecarEvent removed(Object source, String instanceId, String podName, String namespace) {
        return new SidecarEvent(source, "SIDECAR_REMOVED", instanceId, podName, namespace, null);
    }

    public static SidecarEvent healthy(Object source, String instanceId, String podName, String namespace) {
        return new SidecarEvent(source, "SIDECAR_HEALTHY", instanceId, podName, namespace, null);
    }

    public static SidecarEvent unhealthy(Object source, String instanceId, String podName, String namespace) {
        return new SidecarEvent(source, "SIDECAR_UNHEALTHY", instanceId, podName, namespace, null);
    }
}
