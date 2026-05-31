package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.ChunkCreateDTO;
import com.modelguard.dto.DocumentPipelineDTO;
import com.modelguard.dto.DocumentTaskDTO;
import com.modelguard.entity.DocumentChunk;
import com.modelguard.entity.DocumentPipeline;
import com.modelguard.entity.DocumentTask;
import com.modelguard.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/pipelines")
    public Mono<ApiResponse<DocumentPipeline>> createPipeline(@Valid @RequestBody DocumentPipelineDTO dto) {
        return documentService.createPipeline(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/pipelines/{pipelineId}")
    public Mono<ApiResponse<DocumentPipeline>> getPipeline(@PathVariable String pipelineId) {
        return documentService.getPipeline(pipelineId)
                .map(ApiResponse::success);
    }

    @GetMapping("/pipelines")
    public Mono<ApiResponse<PageResult<DocumentPipeline>>> listPipelines(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return documentService.pagePipelines(status, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PutMapping("/pipelines/{pipelineId}")
    public Mono<ApiResponse<DocumentPipeline>> updatePipeline(
            @PathVariable String pipelineId,
            @Valid @RequestBody DocumentPipelineDTO dto) {
        return documentService.updatePipeline(pipelineId, dto)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/pipelines/{pipelineId}")
    public Mono<ApiResponse<Void>> deletePipeline(@PathVariable String pipelineId) {
        return documentService.deletePipeline(pipelineId)
                .then(Mono.just(ApiResponse.success()));
    }

    @PostMapping("/tasks")
    public Mono<ApiResponse<DocumentTask>> submitTask(@Valid @RequestBody DocumentTaskDTO dto) {
        return documentService.submitTask(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ApiResponse<DocumentTask>> getTask(@PathVariable String taskId) {
        return documentService.getTask(taskId)
                .map(ApiResponse::success);
    }

    @PutMapping("/tasks/{taskId}/status")
    public Mono<ApiResponse<DocumentTask>> updateTaskStatus(
            @PathVariable String taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) Double progress) {
        return documentService.updateTaskStatus(taskId, status, phase, progress)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/fail")
    public Mono<ApiResponse<DocumentTask>> markTaskFailed(
            @PathVariable String taskId,
            @RequestBody Map<String, String> body) {
        String errorDetail = body.get("errorDetail");
        return documentService.markTaskFailed(taskId, errorDetail)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Mono<ApiResponse<DocumentTask>> markTaskCompleted(
            @PathVariable String taskId,
            @RequestParam int totalChunks) {
        return documentService.markTaskCompleted(taskId, totalChunks)
                .map(ApiResponse::success);
    }

    @GetMapping("/tasks")
    public Mono<ApiResponse<PageResult<DocumentTask>>> listTasks(
            @RequestParam(required = false) String pipelineId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return documentService.pageTasks(pipelineId, status, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @GetMapping("/tasks/{taskId}/progress")
    public Mono<ApiResponse<Map<String, Object>>> getTaskProgress(@PathVariable String taskId) {
        return documentService.getTaskProgress(taskId)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public Mono<ApiResponse<Void>> retryTask(@PathVariable String taskId) {
        return documentService.retryTask(taskId)
                .then(Mono.just(ApiResponse.success()));
    }

    @PostMapping("/chunks")
    public Mono<ApiResponse<DocumentChunk>> createChunk(@Valid @RequestBody ChunkCreateDTO dto) {
        return documentService.createChunk(dto)
                .map(ApiResponse::created);
    }

    @PostMapping("/chunks/batch")
    public Mono<ApiResponse<List<DocumentChunk>>> batchCreateChunks(@Valid @RequestBody List<ChunkCreateDTO> chunks) {
        return documentService.batchCreateChunks(chunks)
                .map(ApiResponse::created);
    }

    @GetMapping("/tasks/{taskId}/chunks")
    public Mono<ApiResponse<PageResult<DocumentChunk>>> listTaskChunks(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return documentService.pageTaskChunks(taskId, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PostMapping("/split")
    public Mono<ApiResponse<List<String>>> smartSplit(
            @RequestBody Map<String, Object> body) {
        String content = (String) body.get("content");
        int chunkSize = body.get("chunkSize") != null ?
                ((Number) body.get("chunkSize")).intValue() : 512;
        int chunkOverlap = body.get("chunkOverlap") != null ?
                ((Number) body.get("chunkOverlap")).intValue() : 50;
        String separator = (String) body.get("separator");
        return documentService.smartSplit(content, chunkSize, chunkOverlap, separator)
                .map(ApiResponse::success);
    }
}
