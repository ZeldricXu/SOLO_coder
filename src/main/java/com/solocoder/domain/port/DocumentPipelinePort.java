package com.solocoder.domain.port;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface DocumentPipelinePort {

    Mono<String> parseDocument(String fileName, InputStream content);

    Flux<String> splitDocument(String documentId, int chunkSize, int chunkOverlap);

    Flux<float[]> vectorizeChunks(String documentId, List<String> chunks);

    Mono<Void> storeVectors(String documentId, List<float[]> vectors, List<Map<String, Object>> metadata);

    Flux<Map<String, Object>> searchSimilarVectors(float[] queryVector, int topK);

    Mono<Map<String, Object>> processFullPipeline(String fileName, InputStream content);

    Mono<String> getDocumentContent(String documentId);
}
