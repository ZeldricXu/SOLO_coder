package com.datapipeline.api;

import com.datapipeline.common.dto.ApiResponse;
import com.datapipeline.common.dto.resource.*;
import com.datapipeline.common.exception.BusinessException;
import com.datapipeline.common.exception.ValidationError;
import com.datapipeline.common.model.Entity;
import com.datapipeline.common.model.RunInstance;

import com.datapipeline.common.util.IdGenerator;
import com.datapipeline.core.CoreProcessor;
import com.datapipeline.core.ProcessResult;
import com.datapipeline.core.RequestContext;
import com.datapipeline.data.repository.ResourceRepository;
import com.datapipeline.data.repository.RunInstanceRepository;
import com.datapipeline.monitoring.alert.AlertRuleEngine;
import com.datapipeline.monitoring.stats.StatisticsCollector;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final CoreProcessor coreProcessor;
    private final ResourceRepository resourceRepository;
    private final RunInstanceRepository runInstanceRepository;
    private final StatisticsCollector statisticsCollector;
    private final AlertRuleEngine alertRuleEngine;

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<ResourceCreateResponse>>> createResource(
            @Valid @RequestBody Mono<ResourceCreateRequest> requestMono) {

        return requestMono.map(request -> {
            statisticsCollector.incrementCounter("resource_create_requests");

            String id = IdGenerator.generate("rsc");
            Entity entity = Entity.builder()
                    .id(id)
                    .type(request.getType())
                    .status("provisioning")
                    .attributes(new HashMap<>(request.getConfig()))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            resourceRepository.save(entity);

            Map<String, Object> labels = request.getLabels();
            if (labels != null) {
                for (Map.Entry<String, String> entry : labels.entrySet()) {
                    entity.getAttributes().put("label_" + entry.getKey(), entry.getValue());
                }
                resourceRepository.save(entity);
            }

            log.info("Resource created: id={}, type={}", id, request.getType());
            statisticsCollector.incrementCounter("resource_created");

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created(ResourceCreateResponse.builder()
                            .id(id)
                            .status("provisioning")
                            .build()));
        });
    }

    @GetMapping("/{id}/status")
    public Mono<ResponseEntity<ApiResponse<ResourceStatusResponse>>> getResourceStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            statisticsCollector.incrementCounter("resource_status_queries");

            Entity entity = resourceRepository.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("Resource not found: " + id));

            Optional<RunInstance> latestRun = runInstanceRepository.findLatestByEntityId(id);

            ResourceStatusResponse response = ResourceStatusResponse.builder()
                    .id(id)
                    .status(entity.getStatus())
                    .progress(latestRun.map(RunInstance::getProgress).orElse(0.0))
                    .build();

            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @PostMapping("/batch")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResponse>>> batchOperation(
            @Valid @RequestBody Mono<BatchOperationRequest> requestMono) {

        return requestMono.map(request -> {
            statisticsCollector.incrementCounter("batch_operations");

            String batchId = IdGenerator.generate("batch");
            List<BatchOperationResponse.OperationResult> results = new ArrayList<>();

            for (BatchOperationRequest.Operation op : request.getOperations()) {
                try {
                    boolean success = processOperation(op);
                    results.add(BatchOperationResponse.OperationResult.builder()
                            .id(op.getId())
                            .action(op.getAction())
                            .success(success)
                            .message(success ? "Operation successful" : "Operation failed")
                            .build());
                } catch (Exception e) {
                    results.add(BatchOperationResponse.OperationResult.builder()
                            .id(op.getId())
                            .action(op.getAction())
                            .success(false)
                            .message(e.getMessage())
                            .build());
                }
            }

            return ResponseEntity.ok(ApiResponse.success(BatchOperationResponse.builder()
                    .batchId(batchId)
                    .results(results)
                    .build()));
        });
    }

    @PostMapping("/process")
    public Mono<ResponseEntity<ApiResponse<Object>>> processResource(@RequestBody Mono<Map<String, Object>> payloadMono) {
        return payloadMono.map(payload -> {
            statisticsCollector.incrementCounter("process_requests");

            Map<String, Object> params = new HashMap<>();
            params.put("payload", payload);

            RequestContext ctx = RequestContext.builder()
                    .requestId(IdGenerator.generate("req"))
                    .traceId(UUID.randomUUID().toString())
                    .namespace("default")
                    .params(params)
                    .payload(payload)
                    .build();

            ProcessResult result = coreProcessor.execute(ctx);

            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(result.getData()));
            } else if (result.getStatus() == ProcessResult.Status.TIMEOUT) {
                return ResponseEntity.status(504)
                        .body(ApiResponse.error(504, result.getMessage()));
            } else {
                return ResponseEntity.status(500)
                        .body(ApiResponse.error(500, result.getMessage()));
            }
        });
    }

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<List<Entity>>>> listResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {

        return Mono.fromCallable(() -> {
            List<Entity> result;
            if (type != null && status != null) {
                result = resourceRepository.findByType(type).stream()
                        .filter(e -> status.equals(e.getStatus()))
                        .collect(Collectors.toList());
            } else if (type != null) {
                result = resourceRepository.findByType(type);
            } else if (status != null) {
                result = resourceRepository.findByStatus(status);
            } else {
                result = resourceRepository.findAll();
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        });
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ApiResponse<Entity>>> getResource(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Entity entity = resourceRepository.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("Resource not found: " + id));
            return ResponseEntity.ok(ApiResponse.success(entity));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteResource(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            if (!resourceRepository.existsById(id)) {
                throw BusinessException.notFound("Resource not found: " + id);
            }
            resourceRepository.deleteById(id);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }

    private boolean processOperation(BatchOperationRequest.Operation op) {
        if (!resourceRepository.existsById(op.getId())) {
            return false;
        }

        Optional<Entity> entityOpt = resourceRepository.findById(op.getId());
        if (entityOpt.isEmpty()) {
            return false;
        }

        Entity entity = entityOpt.get();
        switch (op.getAction().toLowerCase()) {
            case "restart" -> {
                entity.setStatus("restarting");
                resourceRepository.save(entity);
                return true;
            }
            case "stop" -> {
                entity.setStatus("stopped");
                resourceRepository.save(entity);
                return true;
            }
            case "start" -> {
                entity.setStatus("running");
                resourceRepository.save(entity);
                return true;
            }
            case "delete" -> {
                resourceRepository.deleteById(op.getId());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @ExceptionHandler(ValidationError.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationError(ValidationError e) {
        log.warn("Validation error: {}", e.getMessage());
        return ResponseEntity.status(422).body(ApiResponse.error(422, e.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode()).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

}
