package com.observability.gateway.service;

import com.observability.common.dto.ResourceCreateRequest;
import com.observability.common.entity.ResourceEntity;
import com.observability.common.entity.RunInstanceEntity;
import com.observability.common.enums.ResourceStatus;
import com.observability.common.enums.RunPhase;
import com.observability.common.util.IdGenerator;
import com.observability.common.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;

@Component
public class ResourceFactory {

    public ResourceEntity createResourceEntity(ResourceCreateRequest request, String namespace) {
        ResourceEntity resource = new ResourceEntity();
        resource.setResourceId(IdGenerator.generateResourceId());
        resource.setType(request.getType());
        resource.setStatus(ResourceStatus.PROVISIONING.getCode());
        resource.setNamespace(request.getNamespace() != null ? request.getNamespace() : namespace);
        resource.setConfig(JsonUtil.toJson(request.getConfig()));
        resource.setLabels(JsonUtil.toJson(request.getLabels()));
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    public RunInstanceEntity createRunInstance(String resourceId, String traceId) {
        RunInstanceEntity runInstance = new RunInstanceEntity();
        runInstance.setRunId(IdGenerator.generateRunId());
        runInstance.setEntityId(resourceId);
        runInstance.setPhase(RunPhase.INITIALIZING.getCode());
        runInstance.setProgress(0.0);
        runInstance.setStartedAt(LocalDateTime.now());
        runInstance.setTraceId(traceId);
        return runInstance;
    }
}
