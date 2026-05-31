package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.DocumentPipelinePort;
import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentPipelineService {

    private final DocumentPipelinePort documentPipelinePort;
    private final StructuredLoggerPort logger;

    @Value("${document.pipeline.chunk-size:1000}")
    private int chunkSize;

    @Value("${document.pipeline.chunk-overlap:200}")
    private int chunkOverlap;

    public Mono<ApiResponse<Map<String, Object>>> processFullPipeline(String fileName, InputStream content) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "fileName", fileName
        );
        logger.info("开始文档处理流水线", context);

        return documentPipelinePort.processFullPipeline(fileName, content)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("文档处理失败", e, context);
                    return Mono.just(ApiResponse.error(500, "文档处理失败: " + e.getMessage()));
                });
    }

    public Mono<ApiResponse<String>> parseDocument(String fileName, InputStream content) {
        return documentPipelinePort.parseDocument(fileName, content)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(ApiResponse.error(500, e.getMessage())));
    }

    public Mono<ApiResponse<Flux<String>>> splitDocument(String documentId) {
        return Mono.just(ApiResponse.success(
                documentPipelinePort.splitDocument(documentId, chunkSize, chunkOverlap)
        ));
    }

    public Mono<ApiResponse<Flux<Map<String, Object>>>> searchSimilarVectors(float[] queryVector, int topK) {
        return Mono.just(ApiResponse.success(
                documentPipelinePort.searchSimilarVectors(queryVector, topK)
        ));
    }

    public Mono<ApiResponse<String>> getDocumentContent(String documentId) {
        return documentPipelinePort.getDocumentContent(documentId)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "文档不存在")));
    }
}
