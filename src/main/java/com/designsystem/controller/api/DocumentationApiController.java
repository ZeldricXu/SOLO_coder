package com.designsystem.controller.api;

import com.designsystem.common.Result;
import com.designsystem.entity.ComponentDoc;
import com.designsystem.entity.ComponentProp;
import com.designsystem.service.DocumentationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/docs")
public class DocumentationApiController {

    private final DocumentationService docService;

    public DocumentationApiController(DocumentationService docService) {
        this.docService = docService;
    }

    @PostMapping("/extract/props")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<List<ComponentProp>> extractProps(
            @RequestParam Long versionId,
            @RequestParam MultipartFile file,
            @RequestParam String framework) throws IOException {
        return Result.success(docService.extractPropsFromSource(versionId, file, framework));
    }

    @PostMapping("/extract/docs")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<List<ComponentDoc>> extractDocs(
            @RequestParam Long versionId,
            @RequestParam MultipartFile file) throws IOException {
        return Result.success(docService.extractDocsFromSource(versionId, file));
    }

    @GetMapping("/version/{versionId}")
    public Result<List<ComponentDoc>> getByVersionId(@PathVariable Long versionId) {
        return Result.success(docService.getDocsByVersionId(versionId));
    }

    @GetMapping("/{id}")
    public Result<ComponentDoc> getById(@PathVariable Long id) {
        return Result.success(docService.getDocById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<ComponentDoc> update(@PathVariable Long id, @RequestBody ComponentDoc doc) {
        doc.setId(id);
        return Result.success(docService.updateDoc(doc));
    }

    @GetMapping("/preview/{id}")
    public Result<String> getPreviewHtml(@PathVariable Long id,
                                         @RequestParam(required = false) String code) {
        return Result.success(docService.generateLivePreviewHtml(id, code));
    }

    @PostMapping("/markdown")
    public Result<String> renderMarkdown(@RequestBody String markdown) {
        return Result.success(docService.renderMarkdownToHtml(markdown));
    }
}
