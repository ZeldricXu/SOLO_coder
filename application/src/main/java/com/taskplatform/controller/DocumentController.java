package com.taskplatform.controller;

import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.document.DocumentPipelineService;
import com.taskplatform.persistence.entity.Document;
import com.taskplatform.persistence.entity.DocumentChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentPipelineService documentPipelineService;

    @PostMapping("/upload")
    public ApiResponse<Document> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String createdBy) throws IOException {
        return ApiResponse.created(documentPipelineService.uploadDocument(
                file, title, createdBy != null ? createdBy : "system"));
    }

    @GetMapping("/{docId}")
    public ApiResponse<Document> getDocument(@PathVariable String docId) {
        return ApiResponse.success(documentPipelineService.getDocument(docId));
    }

    @PostMapping("/{docId}/vectorize")
    public ApiResponse<List<DocumentChunk>> vectorizeDocument(
            @PathVariable String docId,
            @RequestBody(required = false) Map<String, String> request) {
        String embeddingModel = request != null ?
                (String) request.getOrDefault("embeddingModel", "text-embedding-ada-002") :
                "text-embedding-ada-002";
        return ApiResponse.success(documentPipelineService.vectorizeChunks(docId, embeddingModel));
    }
}
