package com.parking.platform.document.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.document.entity.Document;
import com.parking.platform.document.service.DocumentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ApiResponse<Document> create(@RequestBody Document doc) {
        return ApiResponse.created(documentService.create(doc));
    }

    @GetMapping("/{id}")
    public ApiResponse<Document> get(@PathVariable String id) {
        Document doc = documentService.get(id);
        return doc != null ? ApiResponse.success(doc) : ApiResponse.notFound("Document not found");
    }

    @PutMapping("/{id}")
    public ApiResponse<Document> update(@PathVariable String id, @RequestBody Document doc) {
        Document updated = documentService.update(id, doc);
        return updated != null ? ApiResponse.success(updated) : ApiResponse.notFound("Document not found");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        boolean deleted = documentService.delete(id);
        return deleted ? ApiResponse.noContent() : ApiResponse.notFound("Document not found");
    }

    @GetMapping("/search")
    public ApiResponse<List<Document>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) List<String> roles,
            @RequestParam(defaultValue = "20") Integer limit) {
        return ApiResponse.success(documentService.search(q, source, roles, limit));
    }

    @GetMapping
    public ApiResponse<List<Document>> list(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return ApiResponse.success(documentService.listBySource(source, page, size));
    }

    @PostMapping("/{id}/index")
    public ApiResponse<Document> index(@PathVariable String id, @RequestBody(required = false) Document doc) {
        return ApiResponse.success(documentService.index(doc != null ? doc : documentService.get(id)));
    }

    @PostMapping("/reindex")
    public ApiResponse<Integer> reindex(@RequestParam(required = false) String source) {
        return ApiResponse.success(documentService.reindex(source));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        return ApiResponse.success(documentService.getStatistics());
    }
}
