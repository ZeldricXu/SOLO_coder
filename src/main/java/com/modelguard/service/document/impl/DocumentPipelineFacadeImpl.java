package com.modelguard.service.document.impl;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.DocumentPipelineCreateRequest;
import com.modelguard.dto.request.DocumentTaskCreateRequest;
import com.modelguard.dto.response.DocumentPipelineResponse;
import com.modelguard.dto.response.DocumentTaskResponse;
import com.modelguard.dto.response.TaskProgressResponse;
import com.modelguard.service.document.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineFacadeImpl implements DocumentPipelineFacade {

    private final DocumentPipelineService documentPipelineService;
    private final DocumentTaskService documentTaskService;
    private final DocumentChunkService documentChunkService;
    private final DocumentParsingService documentParsingService;

    @Override
    public Mono<DocumentPipelineResponse> createPipeline(DocumentPipelineCreateRequest request) {
        return documentPipelineService.createPipeline(request);
    }

    @Override
    public Mono<DocumentPipelineResponse> getPipeline(String pipelineId) {
        return documentPipelineService.getPipeline(pipelineId);
    }

    @Override
    public Mono<DocumentPipelineResponse> enablePipeline(String pipelineId) {
        return documentPipelineService.enablePipeline(pipelineId);
    }

    @Override
    public Mono<DocumentPipelineResponse> disablePipeline(String pipelineId) {
        return documentPipelineService.disablePipeline(pipelineId);
    }

    @Override
    public Mono<PageResult<DocumentPipelineResponse>> pagePipelines(String status, int pageNum, int pageSize) {
        return documentPipelineService.pagePipelines(status, pageNum, pageSize);
    }

    @Override
    public Mono<Map<String, Object>> validatePipeline(String pipelineId) {
        return documentPipelineService.validatePipelineConfig(pipelineId);
    }

    @Override
    public Mono<DocumentTaskResponse> submitTask(DocumentTaskCreateRequest request) {
        return documentTaskService.submitTask(request);
    }

    @Override
    public Mono<DocumentTaskResponse> getTask(String taskId) {
        return documentTaskService.getTask(taskId);
    }

    @Override
    public Mono<TaskProgressResponse> getTaskProgress(String taskId) {
        return documentTaskService.getTaskProgress(taskId);
    }

    @Override
    public Mono<PageResult<DocumentTaskResponse>> pageTasks(String pipelineId, String status, int pageNum, int pageSize) {
        return documentTaskService.pageTasksByPipeline(pipelineId, status, pageNum, pageSize);
    }

    @Override
    public Mono<DocumentTaskResponse> markTaskCompleted(String taskId, Integer chunkCount, Integer totalTokens) {
        return documentTaskService.markTaskCompleted(taskId, chunkCount, totalTokens);
    }

    @Override
    public Mono<DocumentTaskResponse> markTaskFailed(String taskId, String errorMessage) {
        return documentTaskService.markTaskFailed(taskId, errorMessage);
    }

    @Override
    public Mono<Boolean> cancelTask(String taskId) {
        return documentTaskService.cancelTask(taskId);
    }

    @Override
    public Mono<List<String>> splitDocument(String content, int chunkSize, int overlapSize) {
        return documentChunkService.smartSplitDocument(content, chunkSize, overlapSize);
    }

    @Override
    public Mono<Map<String, Object>> parseDocument(String filePath, String fileType) {
        return documentParsingService.extractText(filePath, fileType)
                .flatMap(text -> documentParsingService.cleanText(text))
                .flatMap(cleanedText -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("filePath", filePath);
                    result.put("fileType", fileType);
                    result.put("rawText", cleanedText);
                    result.put("charCount", cleanedText.length());

                    return documentParsingService.splitIntoSentences(cleanedText)
                            .flatMap(sentences -> {
                                result.put("sentenceCount", sentences.size());
                                return documentParsingService.summarizeDocument(cleanedText, 500)
                                        .flatMap(summary -> {
                                            result.put("summary", summary);
                                            return documentParsingService.extractKeywords(cleanedText, 10)
                                                    .map(keywords -> {
                                                        result.put("keywords", keywords);
                                                        return result;
                                                    });
                                        });
                            });
                });
    }

    @Override
    public Mono<Map<String, Object>> getPipelineStats(String pipelineId) {
        return documentTaskService.calculateTaskStats(pipelineId);
    }
}
