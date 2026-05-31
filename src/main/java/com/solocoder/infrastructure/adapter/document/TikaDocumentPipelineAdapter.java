package com.solocoder.infrastructure.adapter.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solocoder.domain.port.DocumentPipelinePort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class TikaDocumentPipelineAdapter implements DocumentPipelinePort {

    private final Tika tika = new Tika();
    private final EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    private final EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    private final ObjectMapper objectMapper;

    private final Map<String, String> documentContentStore = new ConcurrentHashMap<>();
    private final Map<String, List<String>> documentChunksStore = new ConcurrentHashMap<>();
    private final Map<String, List<float[]>> documentVectorsStore = new ConcurrentHashMap<>();

    @Override
    public Mono<String> parseDocument(String fileName, InputStream content) {
        return Mono.fromCallable(() -> {
            String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
            String text = tika.parseToString(content);
            documentContentStore.put(documentId, text);
            return documentId;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<String> splitDocument(String documentId, int chunkSize, int chunkOverlap) {
        return Mono.fromCallable(() -> {
            String content = documentContentStore.get(documentId);
            if (content == null) {
                return Collections.<String>emptyList();
            }

            List<String> chunks = new ArrayList<>();
            int contentLength = content.length();
            int start = 0;

            while (start < contentLength) {
                int end = Math.min(start + chunkSize, contentLength);
                if (end < contentLength) {
                    int lastPeriod = content.lastIndexOf('.', end);
                    int lastSpace = content.lastIndexOf(' ', end);
                    int splitPoint = Math.max(lastPeriod, lastSpace);
                    if (splitPoint > start) {
                        end = splitPoint + 1;
                    }
                }
                chunks.add(content.substring(start, end).trim());
                start = end - chunkOverlap;
                if (start < 0) start = 0;
            }

            documentChunksStore.put(documentId, chunks);
            return chunks;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<float[]> vectorizeChunks(String documentId, List<String> chunks) {
        return Flux.fromIterable(chunks)
                .flatMap(chunk -> Mono.fromCallable(() -> {
                    TextSegment segment = TextSegment.from(chunk);
                    Embedding embedding = embeddingModel.embed(segment).content();
                    return embedding.vector();
                }).subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .flatMapMany(vectors -> {
                    documentVectorsStore.put(documentId, vectors);
                    return Flux.fromIterable(vectors);
                });
    }

    @Override
    public Mono<Void> storeVectors(String documentId, List<float[]> vectors,
                                    List<Map<String, Object>> metadata) {
        return Mono.fromRunnable(() -> {
            List<String> chunks = documentChunksStore.get(documentId);
            if (chunks != null) {
                for (int i = 0; i < chunks.size(); i++) {
                    TextSegment segment = TextSegment.from(chunks.get(i));
                    Embedding embedding = Embedding.from(vectors.get(i));
                    embeddingStore.add(embedding, segment);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<Map<String, Object>> searchSimilarVectors(float[] queryVector, int topK) {
        return Mono.fromCallable(() -> {
            Embedding queryEmbedding = Embedding.from(queryVector);
            List<EmbeddingMatch<TextSegment>> matches =
                    embeddingStore.findRelevant(queryEmbedding, topK);

            List<Map<String, Object>> results = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : matches) {
                Map<String, Object> result = new HashMap<>();
                result.put("score", match.score());
                result.put("content", match.embedded().text());
                result.put("embeddingId", match.embeddingId());
                results.add(result);
            }
            return results;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Map<String, Object>> processFullPipeline(String fileName, InputStream content) {
        return parseDocument(fileName, content)
                .flatMap(documentId -> {
                    return splitDocument(documentId, 1000, 200)
                            .collectList()
                            .flatMap(chunks -> vectorizeChunks(documentId, chunks)
                                    .collectList()
                                    .flatMap(vectors -> storeVectors(documentId, vectors, new ArrayList<>())
                                            .thenReturn(Map.<String, Object>of(
                                                    "documentId", documentId,
                                                    "chunkCount", chunks.size(),
                                                    "vectorCount", vectors.size(),
                                                    "status", "completed"
                                            ))))
                            .onErrorResume(e -> Mono.just(Map.<String, Object>of(
                                    "documentId", documentId,
                                    "status", "failed",
                                    "error", e.getMessage()
                            )));
                });
    }

    @Override
    public Mono<String> getDocumentContent(String documentId) {
        return Mono.justOrEmpty(documentContentStore.get(documentId));
    }
}
