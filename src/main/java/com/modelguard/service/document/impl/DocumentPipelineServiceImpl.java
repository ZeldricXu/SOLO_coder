package com.modelguard.service.document.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.DocumentPipelineCreateRequest;
import com.modelguard.dto.response.DocumentPipelineResponse;
import com.modelguard.entity.DocumentPipeline;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.DocumentPipelineMapper;
import com.modelguard.service.document.DocumentPipelineService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineServiceImpl implements DocumentPipelineService {

    private final DocumentPipelineMapper documentPipelineMapper;
    private final ObjectMapper objectMapper;

    private static final List<String> VALID_STATUSES = Arrays.asList("DRAFT", "ACTIVE", "INACTIVE", "DEPRECATED");
    private static final List<String> REQUIRED_STAGES = Arrays.asList("parse", "clean", "chunk", "vectorize");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentPipelineResponse> createPipeline(DocumentPipelineCreateRequest request) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            DocumentPipeline pipeline = EntityConverter.toEntity(request);
            pipeline.setPipelineId(IdGeneratorUtil.generatePipelineId());
            pipeline.setStatus("DRAFT");

            documentPipelineMapper.insert(pipeline);
            log.info("Created document pipeline: pipelineId={}, name={}", pipeline.getPipelineId(), pipeline.getName());
            return EntityConverter.toResponse(pipeline);
        });
    }

    @Override
    public Mono<DocumentPipelineResponse> getPipeline(String pipelineId) {
        return getPipelineEntity(pipelineId)
                .map(EntityConverter::toResponse);
    }

    @Override
    public Mono<DocumentPipeline> getPipelineEntity(String pipelineId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentPipeline> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentPipeline::getPipelineId, pipelineId);
            DocumentPipeline pipeline = documentPipelineMapper.selectOne(wrapper);
            if (pipeline == null) {
                throw new ResourceNotFoundException("DocumentPipeline", pipelineId);
            }
            return pipeline;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentPipelineResponse> updatePipeline(String pipelineId, Map<String, Object> updates) {
        return getPipelineEntity(pipelineId)
                .flatMap(pipeline -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    if (updates.containsKey("name")) {
                        pipeline.setName((String) updates.get("name"));
                    }
                    if (updates.containsKey("description")) {
                        pipeline.setDescription((String) updates.get("description"));
                    }
                    if (updates.containsKey("pipelineConfig")) {
                        try {
                            String configJson = objectMapper.writeValueAsString(updates.get("pipelineConfig"));
                            pipeline.setPipelineConfig(configJson);
                        } catch (Exception e) {
                            throw new BusinessException("Failed to serialize pipeline config");
                        }
                    }
                    if (updates.containsKey("chunkSize")) {
                        pipeline.setChunkSize((Integer) updates.get("chunkSize"));
                    }
                    if (updates.containsKey("chunkOverlap")) {
                        pipeline.setChunkOverlap((Integer) updates.get("chunkOverlap"));
                    }

                    documentPipelineMapper.updateById(pipeline);
                    log.info("Updated document pipeline: pipelineId={}", pipelineId);
                    return EntityConverter.toResponse(pipeline);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> deletePipeline(String pipelineId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentPipeline> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentPipeline::getPipelineId, pipelineId);
            int deleted = documentPipelineMapper.delete(wrapper);
            log.info("Deleted document pipeline: pipelineId={}, deleted={}", pipelineId, deleted);
            return deleted > 0;
        });
    }

    @Override
    public Mono<List<DocumentPipelineResponse>> listPipelines(String status) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentPipeline> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DocumentPipeline::getStatus, status);
            }
            wrapper.orderByDesc(DocumentPipeline::getCreatedAt);
            return documentPipelineMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<PageResult<DocumentPipelineResponse>> pagePipelines(String status, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Page<DocumentPipeline> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<DocumentPipeline> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DocumentPipeline::getStatus, status);
            }
            wrapper.orderByDesc(DocumentPipeline::getCreatedAt);
            Page<DocumentPipeline> result = documentPipelineMapper.selectPage(page, wrapper);

            List<DocumentPipelineResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentPipelineResponse> enablePipeline(String pipelineId) {
        return validatePipelineConfig(pipelineId)
                .flatMap(validation -> {
                    if (!Boolean.TRUE.equals(validation.get("valid"))) {
                        throw new BusinessException("Pipeline configuration is invalid: " + validation.get("errors"));
                    }
                    return getPipelineEntity(pipelineId);
                })
                .flatMap(pipeline -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    pipeline.setStatus("ACTIVE");
                    documentPipelineMapper.updateById(pipeline);
                    log.info("Enabled document pipeline: pipelineId={}", pipelineId);
                    return EntityConverter.toResponse(pipeline);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentPipelineResponse> disablePipeline(String pipelineId) {
        return getPipelineEntity(pipelineId)
                .flatMap(pipeline -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    pipeline.setStatus("INACTIVE");
                    documentPipelineMapper.updateById(pipeline);
                    log.info("Disabled document pipeline: pipelineId={}", pipelineId);
                    return EntityConverter.toResponse(pipeline);
                }));
    }

    @Override
    public Mono<Map<String, Object>> validatePipelineConfig(String pipelineId) {
        return getPipelineEntity(pipelineId)
                .map(pipeline -> {
                    Map<String, Object> validation = new HashMap<>();
                    List<String> errors = new java.util.ArrayList<>();

                    if (pipeline.getName() == null || pipeline.getName().isEmpty()) {
                        errors.add("Pipeline name is required");
                    }

                    if (pipeline.getChunkSize() == null || pipeline.getChunkSize() < 100 || pipeline.getChunkSize() > 4000) {
                        errors.add("Chunk size must be between 100 and 4000");
                    }

                    if (pipeline.getChunkOverlap() == null || pipeline.getChunkOverlap() < 0 || pipeline.getChunkOverlap() > 500) {
                        errors.add("Chunk overlap must be between 0 and 500");
                    }

                    Map<String, Object> config = pipeline.getParsedPipelineConfig();
                    if (config == null || config.isEmpty()) {
                        errors.add("Pipeline configuration is required");
                    } else {
                        for (String stage : REQUIRED_STAGES) {
                            if (!config.containsKey(stage)) {
                                errors.add("Missing required stage: " + stage);
                            }
                        }
                    }

                    validation.put("pipelineId", pipelineId);
                    validation.put("valid", errors.isEmpty());
                    validation.put("errors", errors);
                    validation.put("warnings", new java.util.ArrayList<>());

                    return validation;
                });
    }

    @Override
    public Mono<DocumentPipeline> ensurePipelineActive(String pipelineId) {
        return getPipelineEntity(pipelineId)
                .flatMap(pipeline -> {
                    if (!"ACTIVE".equals(pipeline.getStatus())) {
                        throw new BusinessException("Pipeline is not active: " + pipeline.getStatus());
                    }
                    return Mono.just(pipeline);
                });
    }
}
