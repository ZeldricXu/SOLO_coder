package com.contractai.document.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.result.ApiResponse;
import com.contractai.document.dto.DocumentDTO;
import com.contractai.document.entity.Document;
import com.contractai.document.entity.DocumentClause;
import com.contractai.document.entity.DocumentComparison;
import com.contractai.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public ApiResponse<Document> createDocument(@RequestBody DocumentDTO.DocumentCreateDTO dto) {
        return ApiResponse.success(documentService.createDocument(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<Document> updateDocument(@PathVariable Long id, @RequestBody DocumentDTO.DocumentUpdateDTO dto) {
        return ApiResponse.success(documentService.updateDocument(id, dto));
    }

    @GetMapping
    public ApiResponse<Page<Document>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(documentService.listDocuments(page, size, docType, status, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Document> getDocument(@PathVariable Long id) {
        return ApiResponse.success(documentService.getDocument(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ApiResponse.success();
    }

    @PostMapping("/comparisons")
    public ApiResponse<DocumentComparison> createComparison(@RequestBody DocumentDTO.ComparisonCreateDTO dto) {
        return ApiResponse.success(documentService.createComparison(dto));
    }

    @GetMapping("/comparisons/{id}")
    public ApiResponse<DocumentComparison> getComparison(@PathVariable Long id) {
        return ApiResponse.success(documentService.getComparison(id));
    }

    @GetMapping("/comparisons")
    public ApiResponse<Page<DocumentComparison>> listComparisons(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String comparisonType) {
        return ApiResponse.success(documentService.listComparisons(page, size, status, comparisonType));
    }

    @PostMapping("/comparisons/{id}/execute")
    public ApiResponse<DocumentComparison> executeComparison(
            @PathVariable Long id,
            @RequestBody DocumentDTO.ComparisonCreateDTO dto) {
        DocumentComparison comparison = documentService.getComparison(id);
        return ApiResponse.success(documentService.executeComparison(comparison, dto));
    }

    @PostMapping("/clauses")
    public ApiResponse<DocumentClause> createClause(@RequestBody DocumentDTO.ClauseCreateDTO dto) {
        return ApiResponse.success(documentService.createClause(dto));
    }

    @GetMapping("/{documentId}/clauses")
    public ApiResponse<List<DocumentClause>> getDocumentClauses(@PathVariable Long documentId) {
        return ApiResponse.success(documentService.getDocumentClauses(documentId));
    }

    @PostMapping("/clauses/extract")
    public ApiResponse<List<DocumentClause>> extractClauses(@RequestBody DocumentDTO.ClauseExtractDTO dto) {
        return ApiResponse.success(documentService.extractClauses(dto));
    }
}
