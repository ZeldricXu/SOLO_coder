package com.solocoder.presentation.controller;

import com.solocoder.application.service.DocumentPipelineService;
import com.solocoder.domain.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentPipelineController {

    private final DocumentPipelineService documentPipelineService;

    @PostMapping("/process")
    public Mono<ApiResponse<Map<String, Object>>> processDocument(
            @RequestPart("file") Mono<FilePart> filePartMono) {

        return filePartMono.flatMap(filePart -> {
            String fileName = filePart.filename();
            return filePart.content()
                    .reduce(new InputStream[1], (is, dataBuffer) -> {
                        if (is[0] == null) {
                            is[0] = dataBuffer.asInputStream();
                        }
                        return is;
                    })
                    .flatMap(is -> documentPipelineService.processFullPipeline(fileName, is[0]));
        });
    }

    @PostMapping("/parse")
    public Mono<ApiResponse<String>> parseDocument(
            @RequestPart("file") Mono<FilePart> filePartMono) {

        return filePartMono.flatMap(filePart -> {
            String fileName = filePart.filename();
            return filePart.content()
                    .reduce(new InputStream[1], (is, dataBuffer) -> {
                        if (is[0] == null) {
                            is[0] = dataBuffer.asInputStream();
                        }
                        return is;
                    })
                    .flatMap(is -> documentPipelineService.parseDocument(fileName, is[0]));
        });
    }

    @GetMapping("/{documentId}/split")
    public Mono<ApiResponse<Flux<String>>> splitDocument(@PathVariable String documentId) {
        return documentPipelineService.splitDocument(documentId);
    }

    @PostMapping("/search")
    public Mono<ApiResponse<Flux<Map<String, Object>>>> searchSimilar(
            @RequestBody Map<String, Object> request) {
        String queryText = (String) request.get("query");
        int topK = (int) request.getOrDefault("topK", 5);

        float[] queryVector = new float[384];
        for (int i = 0; i < 384; i++) {
            queryVector[i] = (float) Math.random();
        }

        return documentPipelineService.searchSimilarVectors(queryVector, topK);
    }

    @GetMapping("/{documentId}/content")
    public Mono<ApiResponse<String>> getDocumentContent(@PathVariable String documentId) {
        return documentPipelineService.getDocumentContent(documentId);
    }
}
