package com.llmgateway.document.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.document.dto.DocumentUploadDTO;
import com.llmgateway.document.dto.ParseConfigDTO;
import com.llmgateway.document.entity.Document;
import com.llmgateway.document.entity.DocumentChunk;
import com.llmgateway.document.entity.ParseTask;
import com.llmgateway.document.service.DocumentPipelineService;
import com.llmgateway.document.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentPipelineService pipelineService;

    @PostMapping
    public R<Document> uploadDocument(@Valid @RequestBody DocumentUploadDTO dto) {
        Document document = documentService.upload(dto);
        if (dto.getContent() != null) {
            ParseConfigDTO config = new ParseConfigDTO();
            ParseTask task = pipelineService.createParseTask(document.getDocumentId(), config);
            pipelineService.executeParse(task.getTaskId(), dto.getContent(), config);
        }
        return R.created(document);
    }

    @GetMapping("/{documentId}")
    public R<Document> getDocument(@PathVariable String documentId) {
        return R.success(documentService.getById(documentId));
    }

    @GetMapping
    public R<PageResult<Document>> listDocuments(
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(documentService.list(fileType, status, pageNum, pageSize));
    }

    @PutMapping("/{documentId}/status")
    public R<Document> updateDocumentStatus(@PathVariable String documentId, @RequestParam String status) {
        return R.success(documentService.updateStatus(documentId, status));
    }

    @DeleteMapping("/{documentId}")
    public R<Void> deleteDocument(@PathVariable String documentId) {
        documentService.delete(documentId);
        return R.success();
    }

    @PostMapping("/{documentId}/parse")
    public R<ParseTask> parseDocument(
            @PathVariable String documentId,
            @RequestBody(required = false) Map<String, Object> request) {
        ParseConfigDTO config = new ParseConfigDTO();
        String content = null;
        if (request != null) {
            content = (String) request.get("content");
        }
        ParseTask task = pipelineService.createParseTask(documentId, config);
        pipelineService.executeParse(task.getTaskId(), content, config);
        return R.created(task);
    }

    @GetMapping("/tasks/{taskId}")
    public R<ParseTask> getParseTask(@PathVariable String taskId) {
        return R.success(pipelineService.getTask(taskId));
    }

    @GetMapping("/{documentId}/chunks")
    public R<List<DocumentChunk>> getDocumentChunks(@PathVariable String documentId) {
        return R.success(pipelineService.getDocumentChunks(documentId));
    }
}
