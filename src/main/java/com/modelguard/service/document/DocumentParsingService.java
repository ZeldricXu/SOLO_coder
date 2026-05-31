package com.modelguard.service.document;

import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface DocumentParsingService {

    Mono<String> extractText(String filePath, String fileType);

    Mono<String> extractTextFromContent(byte[] content, String fileType);

    Mono<String> cleanText(String rawText);

    Mono<List<String>> splitIntoSentences(String text);

    Mono<List<Map<String, Object>>> splitIntoChunks(String text, int chunkSize, int overlapSize);

    Mono<List<Float>> vectorizeChunk(String chunkText);

    Mono<Map<String, Object>> detectDocumentType(String filePath);

    Mono<Map<String, Object>> extractMetadata(String filePath, String fileType);

    Mono<String> summarizeDocument(String text, int maxLength);

    Mono<List<String>> extractKeywords(String text, int topN);
}
