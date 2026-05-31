package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.request.DocumentPipelineCreateRequest;
import com.modelguard.dto.request.DocumentTaskCreateRequest;
import com.modelguard.dto.response.DocumentPipelineResponse;
import com.modelguard.dto.response.DocumentTaskResponse;
import com.modelguard.dto.response.TaskProgressResponse;
import com.modelguard.service.document.DocumentPipelineFacade;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/document-pipelines")
@RequiredArgsConstructor
public class DocumentPipelineController {

    private final DocumentPipelineFacade documentPipelineFacade;

    @PostMapping("/pipelines")
    @Timed(value = "pipeline.create", description = "Time taken to create document pipeline")
    public Mono<ResponseEntity<ApiResponse<DocumentPipelineResponse>>> createPipeline(
            @Valid @RequestBody DocumentPipelineCreateRequest request) {
        return documentPipelineFacade.createPipeline(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/pipelines/{pipelineId}")
    public Mono<ResponseEntity<ApiResponse<DocumentPipelineResponse>>> getPipeline(
            @PathVariable String pipelineId) {
        return documentPipelineFacade.getPipeline(pipelineId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/pipelines/{pipelineId}/enable")
    public Mono<ResponseEntity<ApiResponse<DocumentPipelineResponse>>> enablePipeline(
            @PathVariable String pipelineId) {
        return documentPipelineFacade.enablePipeline(pipelineId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/pipelines/{pipelineId}/disable")
    public Mono<ResponseEntity<ApiResponse<DocumentPipelineResponse>>> disablePipeline(
            @PathVariable String pipelineId) {
        return documentPipelineFacade.disablePipeline(pipelineId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/pipelines")
    public Mono<ResponseEntity<ApiResponse<PageResult<DocumentPipelineResponse>>>> pagePipelines(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return documentPipelineFacade.pagePipelines(status, pageNum, pageSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/pipelines/{pipelineId}/validate")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> validatePipeline(
            @PathVariable String pipelineId) {
        return documentPipelineFacade.validatePipeline(pipelineId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks")
    @Timed(value = "document.task.submit", description = "Time taken to submit document task")
    public Mono<ResponseEntity<ApiResponse<DocumentTaskResponse>>> submitTask(
            @Valid @RequestBody DocumentTaskCreateRequest request) {
        return documentPipelineFacade.submitTask(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<ApiResponse<DocumentTaskResponse>>> getTask(
            @PathVariable String taskId) {
        return documentPipelineFacade.getTask(taskId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/tasks/{taskId}/progress")
    public Mono<ResponseEntity<ApiResponse<TaskProgressResponse>>> getTaskProgress(
            @PathVariable String taskId) {
        return documentPipelineFacade.getTaskProgress(taskId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/tasks")
    public Mono<ResponseEntity<ApiResponse<PageResult<DocumentTaskResponse>>>> pageTasks(
            @RequestParam String pipelineId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return documentPipelineFacade.pageTasks(pipelineId, status, pageNum, pageSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Mono<ResponseEntity<ApiResponse<DocumentTaskResponse>>> markTaskCompleted(
            @PathVariable String taskId,
            @RequestParam(required = false) Integer chunkCount,
            @RequestParam(required = false) Integer totalTokens) {
        return documentPipelineFacade.markTaskCompleted(taskId, chunkCount, totalTokens)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/fail")
    public Mono<ResponseEntity<ApiResponse<DocumentTaskResponse>>> markTaskFailed(
            @PathVariable String taskId,
            @RequestParam String errorMessage) {
        return documentPipelineFacade.markTaskFailed(taskId, errorMessage)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> cancelTask(
            @PathVariable String taskId) {
        return documentPipelineFacade.cancelTask(taskId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/split")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> splitDocument(
            @RequestBody Map<String, Object> request) {
        String content = (String) request.get("content");
        int chunkSize = request.get("chunkSize") != null ?
                ((Number) request.get("chunkSize")).intValue() : 500;
        int overlapSize = request.get("overlapSize") != null ?
                ((Number) request.get("overlapSize")).intValue() : 50;

        return documentPipelineFacade.splitDocument(content, chunkSize, overlapSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/parse")
    @Timed(value = "document.parse", description = "Time taken to parse document")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> parseDocument(
            @RequestBody Map<String, Object> request) {
        String filePath = (String) request.get("filePath");
        String fileType = (String) request.get("fileType");

        return documentPipelineFacade.parseDocument(filePath, fileType)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/pipelines/{pipelineId}/stats")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getPipelineStats(
            @PathVariable String pipelineId) {
        return documentPipelineFacade.getPipelineStats(pipelineId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }
}
