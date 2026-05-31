package com.observability.gateway.service.impl;

import com.observability.common.context.RequestContextHolder;
import com.observability.common.dto.BatchOperationRequest;
import com.observability.common.dto.BatchOperationResponse;
import com.observability.common.dto.ResourceCreateRequest;
import com.observability.common.enums.ResourceStatus;
import com.observability.common.exception.BusinessException;
import com.observability.common.util.IdGenerator;
import com.observability.dal.repository.ResourceRepository;
import com.observability.dal.repository.RunInstanceRepository;
import com.observability.gateway.service.ResourceFactory;
import com.observability.gateway.service.ResourceMutationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceMutationServiceImpl implements ResourceMutationService {

    private final ResourceRepository resourceRepository;
    private final RunInstanceRepository runInstanceRepository;
    private final ResourceFactory resourceFactory;

    @Override
    public Mono<Map<String, Object>> createResource(ResourceCreateRequest request) {
        return RequestContextHolder.get()
                .flatMap(context -> {
                    validateRequest(request);

                    ResourceEntity resource = resourceFactory.createResourceEntity(request, context.getNamespace());
                    resourceRepository.save(resource);

                    RunInstanceEntity runInstance = resourceFactory.createRunInstance(
                            resource.getResourceId(), context.getTraceId());
                    runInstanceRepository.save(runInstance);

                    log.info("Resource created - traceId: {}, resourceId: {}, runId: {}",
                            context.getTraceId(), resource.getResourceId(), runInstance.getRunId());

                    Map<String, Object> result = new HashMap<>();
                    result.put("id", resource.getResourceId());
                    result.put("status", ResourceStatus.PROVISIONING.getCode());
                    result.put("runId", runInstance.getRunId());

                    return Mono.just(result);
                });
    }

    @Override
    public Mono<BatchOperationResponse> batchOperation(BatchOperationRequest request) {
        return RequestContextHolder.get()
                .flatMap(context -> {
                    String batchId = IdGenerator.generateBatchId();
                    List<BatchOperationResponse.OperationResult> results = new ArrayList<>();

                    for (BatchOperationRequest.Operation op : request.getOperations()) {
                        BatchOperationResponse.OperationResult result = new BatchOperationResponse.OperationResult();
                        result.setId(op.getId());
                        result.setAction(op.getAction());

                        try {
                            executeOperation(op);
                            result.setSuccess(true);
                            result.setMessage("Operation successful");
                        } catch (Exception e) {
                            result.setSuccess(false);
                            result.setMessage(e.getMessage());
                        }
                        results.add(result);
                    }

                    BatchOperationResponse response = new BatchOperationResponse();
                    response.setBatchId(batchId);
                    response.setResults(results);

                    return Mono.just(response);
                });
    }

    @Override
    public Mono<Void> startResource(String id) {
        return Mono.fromRunnable(() -> {
            resourceRepository.updateStatus(id, ResourceStatus.RUNNING.getCode());
            log.info("Resource started - id: {}", id);
        });
    }

    @Override
    public Mono<Void> stopResource(String id) {
        return Mono.fromRunnable(() -> {
            resourceRepository.updateStatus(id, ResourceStatus.STOPPED.getCode());
            log.info("Resource stopped - id: {}", id);
        });
    }

    @Override
    public Mono<Void> restartResource(String id) {
        return Mono.fromRunnable(() -> {
            resourceRepository.updateStatus(id, ResourceStatus.PROVISIONING.getCode());
            log.info("Resource restarted - id: {}", id);
        });
    }

    @Override
    public Mono<Void> deleteResource(String id) {
        return Mono.fromRunnable(() -> {
            resourceRepository.deleteByResourceId(id);
            log.info("Resource deleted - id: {}", id);
        });
    }

    private void validateRequest(ResourceCreateRequest request) {
        if (request.getType() == null || request.getType().isBlank()) {
            throw BusinessException.validationError("Resource type is required");
        }
    }

    private void executeOperation(BatchOperationRequest.Operation operation) {
        switch (operation.getAction().toLowerCase()) {
            case "start" -> startResource(operation.getId()).block();
            case "stop" -> stopResource(operation.getId()).block();
            case "restart" -> restartResource(operation.getId()).block();
            case "delete" -> deleteResource(operation.getId()).block();
            default -> throw BusinessException.validationError("Unknown action: " + operation.getAction());
        }
    }
}
