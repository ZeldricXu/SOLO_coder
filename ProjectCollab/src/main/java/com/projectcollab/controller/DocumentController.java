package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.dto.UploadDocumentRequest;
import com.projectcollab.dto.UploadDocumentResponse;
import com.projectcollab.entity.Document;
import com.projectcollab.service.document.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @PostMapping("/upload")
    public ApiResponse<UploadDocumentResponse> uploadDocument(@RequestBody UploadDocumentRequest request) {
        Document document = documentService.uploadDocument(request);
        UploadDocumentResponse response = new UploadDocumentResponse(document.getDocId(), "uploaded");
        return ApiResponse.success(response);
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Document>> getDocumentsByProject(@PathVariable String projectId) {
        List<Document> documents = documentService.getDocumentsByProjectId(projectId);
        return ApiResponse.success(documents);
    }

    @GetMapping("/project/{projectId}/shared")
    public ApiResponse<List<Document>> getSharedDocuments(@PathVariable String projectId) {
        List<Document> documents = documentService.getSharedDocuments(projectId);
        return ApiResponse.success(documents);
    }

    @PostMapping("/{docId}/share")
    public ApiResponse<Document> shareDocument(@PathVariable String docId) {
        Document document = documentService.shareDocument(docId);
        return ApiResponse.success(document);
    }

    @GetMapping("/{docId}")
    public ApiResponse<Document> getDocument(@PathVariable String docId) {
        return documentService.getDocumentById(docId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "文档不存在"));
    }
}
