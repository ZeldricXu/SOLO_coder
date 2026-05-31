package com.smartflow.documentcompare.controller;

import com.smartflow.common.base.Result;
import com.smartflow.persistence.entity.Document;
import com.smartflow.persistence.entity.DocumentCompare;
import com.smartflow.documentcompare.service.DocumentCompareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/document")
@RequiredArgsConstructor
public class DocumentCompareController {

    private final DocumentCompareService documentCompareService;

    @PostMapping
    public Result<Document> createDocument(@RequestBody Document document) {
        Document created = documentCompareService.createDocument(document);
        return Result.success(created);
    }

    @GetMapping("/{documentId}")
    public Result<Document> getDocument(@PathVariable Long documentId) {
        Document document = documentCompareService.getDocument(documentId);
        return Result.success(document);
    }

    @GetMapping
    public Result<List<Document>> listDocuments(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status) {
        List<Document> documents = documentCompareService.listDocuments(category, status);
        return Result.success(documents);
    }

    @PostMapping("/compare")
    public Result<Map<String, Object>> compareDocuments(
            @RequestParam Long leftDocId,
            @RequestParam Long rightDocId,
            @RequestBody(required = false) Map<String, Object> options) {
        Map<String, Object> result = documentCompareService.compareDocuments(leftDocId, rightDocId, options);
        return Result.success(result);
    }

    @GetMapping("/compare/{compareId}")
    public Result<Map<String, Object>> getCompareDetail(@PathVariable Long compareId) {
        Map<String, Object> detail = documentCompareService.getCompareDetail(compareId);
        return Result.success(detail);
    }

    @GetMapping("/compare/list")
    public Result<List<DocumentCompare>> listCompareResults(
            @RequestParam(required = false) Long leftDocId,
            @RequestParam(required = false) Long rightDocId) {
        List<DocumentCompare> results = documentCompareService.listCompareResults(leftDocId, rightDocId);
        return Result.success(results);
    }
}
