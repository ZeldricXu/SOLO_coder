package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Document;
import com.projmanage.service.DocumentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ApiResponse<Document> uploadDocument(@RequestParam String projectId,
                                                @RequestParam String documentName,
                                                @RequestParam(required = false) String documentType,
                                                @RequestParam String filePath,
                                                @RequestParam Long fileSize,
                                                @RequestParam String uploadedBy) {
        Document document = documentService.uploadDocument(projectId, documentName, documentType,
                filePath, fileSize, uploadedBy);
        return ApiResponse.success(document);
    }

    @GetMapping("/{documentId}")
    public ApiResponse<Document> getDocumentById(@PathVariable String documentId) {
        Optional<Document> docOpt = documentService.getDocumentById(documentId);
        if (docOpt.isPresent()) {
            return ApiResponse.success(docOpt.get());
        }
        return ApiResponse.error(404, "文档不存在");
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Document>> getDocumentsByProject(@PathVariable String projectId) {
        return ApiResponse.success(documentService.getDocumentsByProject(projectId));
    }

    @PutMapping("/{documentId}")
    public ApiResponse<Document> updateDocument(@PathVariable String documentId,
                                                 @RequestParam String documentName,
                                                 @RequestParam String filePath,
                                                 @RequestParam Long fileSize) {
        Document document = documentService.updateDocument(documentId, documentName, filePath, fileSize);
        if (document != null) {
            return ApiResponse.success(document);
        }
        return ApiResponse.error(404, "文档不存在");
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String documentId) {
        documentService.deleteDocument(documentId);
        return ApiResponse.success(null);
    }
}
