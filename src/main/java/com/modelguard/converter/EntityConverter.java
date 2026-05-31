package com.modelguard.converter;

import com.modelguard.dto.request.*;
import com.modelguard.dto.response.*;
import com.modelguard.entity.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class EntityConverter {

    private EntityConverter() {
    }

    public static PromptVersion toEntity(PromptVersionCreateRequest request) {
        PromptVersion entity = new PromptVersion();
        entity.setContent(request.getContent());
        entity.setVariables(request.getVariables());
        entity.setDescription(request.getDescription());
        entity.setCreatedBy(request.getCreatedBy());
        return entity;
    }

    public static PromptVersionResponse toResponse(PromptVersion entity) {
        PromptVersionResponse response = new PromptVersionResponse();
        response.setId(entity.getId());
        response.setPromptId(entity.getPromptId());
        response.setVersion(entity.getVersion());
        response.setContent(entity.getContent());
        response.setVariables(entity.getVariables());
        response.setDescription(entity.getDescription());
        response.setCreatedBy(entity.getCreatedBy());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    public static AbExperiment toEntity(AbExperimentCreateRequest request) {
        AbExperiment entity = new AbExperiment();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setPromptId(request.getPromptId());
        entity.setControlGroupPromptId(request.getControlGroupPromptId());
        entity.setControlGroupPromptVersion(request.getControlGroupPromptVersion());
        entity.setExperimentalGroupPromptId(request.getExperimentalGroupPromptId());
        entity.setExperimentalGroupPromptVersion(request.getExperimentalGroupPromptVersion());
        entity.setTrafficSplit(request.getTrafficSplit() != null ? request.getTrafficSplit() : new BigDecimal("0.5"));
        entity.setStatus("DRAFT");
        entity.setCreatedBy(request.getCreatedBy());
        entity.setMetrics(request.getMetrics());
        return entity;
    }

    public static AbExperimentResponse toResponse(AbExperiment entity) {
        AbExperimentResponse response = new AbExperimentResponse();
        response.setId(entity.getId());
        response.setExperimentId(entity.getExperimentId());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setPromptId(entity.getPromptId());
        response.setControlGroupPromptId(entity.getControlGroupPromptId());
        response.setControlGroupPromptVersion(entity.getControlGroupPromptVersion());
        response.setExperimentalGroupPromptId(entity.getExperimentalGroupPromptId());
        response.setExperimentalGroupPromptVersion(entity.getExperimentalGroupPromptVersion());
        response.setTrafficSplit(entity.getTrafficSplit());
        response.setStatus(entity.getStatus());
        response.setCreatedBy(entity.getCreatedBy());
        response.setMetrics(entity.getMetrics());
        response.setStartedAt(entity.getStartedAt());
        response.setEndedAt(entity.getEndedAt());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    public static AbExperimentResult toEntity(ExperimentResultRecordRequest request) {
        AbExperimentResult entity = new AbExperimentResult();
        entity.setExperimentId(request.getExperimentId());
        entity.setUserId(request.getUserId());
        entity.setGroupId(request.getGroupId());
        entity.setPromptVersion(request.getPromptVersion());
        entity.setInputTokens(request.getInputTokens());
        entity.setOutputTokens(request.getOutputTokens());
        entity.setLatencyMs(request.getLatencyMs());
        entity.setScores(request.getScores());
        entity.setMetadata(request.getMetadata());
        return entity;
    }

    public static AbExperimentResultResponse toResponse(AbExperimentResult entity) {
        AbExperimentResultResponse response = new AbExperimentResultResponse();
        response.setId(entity.getId());
        response.setResultId(entity.getResultId());
        response.setExperimentId(entity.getExperimentId());
        response.setUserId(entity.getUserId());
        response.setGroupId(entity.getGroupId());
        response.setPromptVersion(entity.getPromptVersion());
        response.setInputTokens(entity.getInputTokens());
        response.setOutputTokens(entity.getOutputTokens());
        response.setLatencyMs(entity.getLatencyMs());
        response.setScores(entity.getScores());
        response.setMetadata(entity.getMetadata());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    public static DocumentPipeline toEntity(DocumentPipelineCreateRequest request) {
        DocumentPipeline entity = new DocumentPipeline();
        entity.setName(request.getName());
        entity.setDescription(request.getDescription());
        entity.setSourceType(request.getSourceType());
        entity.setChunkSize(request.getChunkSize() != null ? request.getChunkSize() : 500);
        entity.setChunkOverlap(request.getChunkOverlap() != null ? request.getChunkOverlap() : 50);
        entity.setEmbeddingModel(request.getEmbeddingModel());
        entity.setVectorDimension(request.getVectorDimension());
        entity.setStatus("ACTIVE");
        entity.setCreatedBy(request.getCreatedBy());
        entity.setConfig(request.getConfig());
        return entity;
    }

    public static DocumentPipelineResponse toResponse(DocumentPipeline entity) {
        DocumentPipelineResponse response = new DocumentPipelineResponse();
        response.setId(entity.getId());
        response.setPipelineId(entity.getPipelineId());
        response.setName(entity.getName());
        response.setDescription(entity.getDescription());
        response.setSourceType(entity.getSourceType());
        response.setChunkSize(entity.getChunkSize());
        response.setChunkOverlap(entity.getChunkOverlap());
        response.setEmbeddingModel(entity.getEmbeddingModel());
        response.setVectorDimension(entity.getVectorDimension());
        response.setStatus(entity.getStatus());
        response.setCreatedBy(entity.getCreatedBy());
        response.setConfig(entity.getConfig());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    public static DocumentTask toEntity(DocumentTaskCreateRequest request) {
        DocumentTask entity = new DocumentTask();
        entity.setPipelineId(request.getPipelineId());
        entity.setFileName(request.getFileName());
        entity.setFilePath(request.getFilePath());
        entity.setFileSize(request.getFileSize());
        entity.setFileType(request.getFileType());
        entity.setVectorStore(request.getVectorStore());
        entity.setStatus("PENDING");
        entity.setPhase("INIT");
        entity.setProgress(BigDecimal.ZERO);
        entity.setStartedAt(LocalDateTime.now());
        entity.setMetadata(request.getMetadata());
        return entity;
    }

    public static DocumentTaskResponse toResponse(DocumentTask entity) {
        DocumentTaskResponse response = new DocumentTaskResponse();
        response.setId(entity.getId());
        response.setTaskId(entity.getTaskId());
        response.setPipelineId(entity.getPipelineId());
        response.setFileName(entity.getFileName());
        response.setFilePath(entity.getFilePath());
        response.setFileSize(entity.getFileSize());
        response.setFileType(entity.getFileType());
        response.setStatus(entity.getStatus());
        response.setPhase(entity.getPhase());
        response.setProgress(entity.getProgress());
        response.setTotalChunks(entity.getTotalChunks());
        response.setVectorStore(entity.getVectorStore());
        response.setErrorDetail(entity.getErrorDetail());
        response.setRetryCount(entity.getRetryCount());
        response.setMetadata(entity.getMetadata());
        response.setStartedAt(entity.getStartedAt());
        response.setCompletedAt(entity.getCompletedAt());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    public static DocumentChunk toEntity(DocumentChunkCreateRequest request) {
        DocumentChunk entity = new DocumentChunk();
        entity.setTaskId(request.getTaskId());
        entity.setChunkIndex(request.getChunkIndex());
        entity.setContent(request.getContent());
        entity.setWordCount(request.getWordCount());
        entity.setTokenCount(request.getTokenCount());
        entity.setEmbedding(request.getEmbedding());
        entity.setMetadata(request.getMetadata());
        return entity;
    }

    public static DocumentChunkResponse toResponse(DocumentChunk entity) {
        DocumentChunkResponse response = new DocumentChunkResponse();
        response.setId(entity.getId());
        response.setChunkId(entity.getChunkId());
        response.setTaskId(entity.getTaskId());
        response.setChunkIndex(entity.getChunkIndex());
        response.setContent(entity.getContent());
        response.setWordCount(entity.getWordCount());
        response.setTokenCount(entity.getTokenCount());
        response.setEmbedding(entity.getEmbedding());
        response.setMetadata(entity.getMetadata());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    public static GpuNode toEntity(GpuNodeRegisterRequest request) {
        GpuNode entity = new GpuNode();
        entity.setHostname(request.getHostname());
        entity.setIpAddress(request.getIpAddress());
        entity.setGpuCount(request.getGpuCount());
        entity.setGpuModel(request.getGpuModel());
        entity.setTotalGpuMemoryGb(request.getTotalGpuMemoryGb());
        entity.setAvailableGpuMemoryGb(request.getTotalGpuMemoryGb());
        entity.setStatus("ONLINE");
        entity.setLabels(request.getLabels());
        entity.setLastHeartbeat(LocalDateTime.now());
        return entity;
    }

    public static GpuNodeResponse toResponse(GpuNode entity) {
        GpuNodeResponse response = new GpuNodeResponse();
        response.setId(entity.getId());
        response.setNodeId(entity.getNodeId());
        response.setHostname(entity.getHostname());
        response.setIpAddress(entity.getIpAddress());
        response.setGpuCount(entity.getGpuCount());
        response.setGpuModel(entity.getGpuModel());
        response.setTotalGpuMemoryGb(entity.getTotalGpuMemoryGb());
        response.setAvailableGpuMemoryGb(entity.getAvailableGpuMemoryGb());
        response.setStatus(entity.getStatus());
        response.setLabels(entity.getLabels());
        response.setLastHeartbeat(entity.getLastHeartbeat());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    public static GpuTask toEntity(GpuTaskSubmitRequest request) {
        GpuTask entity = new GpuTask();
        entity.setName(request.getName());
        entity.setTaskType(request.getTaskType());
        entity.setPriority(request.getPriority() != null ? request.getPriority() : 5);
        entity.setRequiredGpuCount(request.getRequiredGpuCount() != null ? request.getRequiredGpuCount() : 1);
        entity.setRequiredGpuMemoryGb(request.getRequiredGpuMemoryGb());
        entity.setEstimatedRuntimeMs(request.getEstimatedRuntimeMs());
        entity.setStatus("PENDING");
        entity.setPreemptible(request.getPreemptible() != null ? request.getPreemptible() : true);
        entity.setPayload(request.getPayload());
        entity.setSubmittedBy(request.getSubmittedBy());
        entity.setLabels(request.getLabels());
        return entity;
    }

    public static GpuTaskResponse toResponse(GpuTask entity) {
        GpuTaskResponse response = new GpuTaskResponse();
        response.setId(entity.getId());
        response.setTaskId(entity.getTaskId());
        response.setName(entity.getName());
        response.setTaskType(entity.getTaskType());
        response.setPriority(entity.getPriority());
        response.setRequiredGpuCount(entity.getRequiredGpuCount());
        response.setRequiredGpuMemoryGb(entity.getRequiredGpuMemoryGb());
        response.setEstimatedRuntimeMs(entity.getEstimatedRuntimeMs());
        response.setStatus(entity.getStatus());
        response.setNodeId(entity.getNodeId());
        response.setGpuIndices(entity.getGpuIndices());
        response.setProgress(entity.getProgress());
        response.setPreemptible(entity.getPreemptible());
        response.setPayload(entity.getPayload());
        response.setSubmittedBy(entity.getSubmittedBy());
        response.setLabels(entity.getLabels());
        response.setErrorDetail(entity.getErrorDetail());
        response.setSubmittedAt(entity.getSubmittedAt());
        response.setScheduledAt(entity.getScheduledAt());
        response.setStartedAt(entity.getStartedAt());
        response.setCompletedAt(entity.getCompletedAt());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}
