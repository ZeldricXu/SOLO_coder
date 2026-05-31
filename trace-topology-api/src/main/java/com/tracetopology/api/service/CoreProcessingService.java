package com.tracetopology.api.service;

import com.tracetopology.domain.entity.Entity;
import com.tracetopology.domain.entity.RunInstance;

import java.util.List;
import java.util.Map;

public interface CoreProcessingService {

    Map<String, Object> process(String traceId, String namespace, Map<String, Object> payload,
                                Map<String, Object> params);

    List<Map<String, Object>> processBatch(String traceId, String namespace,
                                            List<Map<String, Object>> payloads,
                                            Map<String, Object> params);

    RunInstance startProcessing(String entityId, Map<String, Object> config);

    RunInstance getProcessingStatus(String runId);

    Entity createEntity(String type, Map<String, Object> attributes);

    Entity getEntity(String entityId);

    Entity updateEntity(String entityId, Map<String, Object> updates);

    void deleteEntity(String entityId);

    void cancelProcessing(String runId);
}
