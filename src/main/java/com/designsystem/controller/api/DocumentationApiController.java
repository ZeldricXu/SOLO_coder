package com.designsystem.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.common.PageQuery;
import com.designsystem.common.Result;
import com.designsystem.entity.ComponentDoc;
import com.designsystem.entity.ComponentProp;
import com.designsystem.entity.DocParseRecord;
import com.designsystem.service.DocumentationService;
import com.designsystem.service.IncrementalDocService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
public class DocumentationApiController {

    private final DocumentationService docService;
    private final IncrementalDocService incrementalDocService;

    public DocumentationApiController(DocumentationService docService,
                                       IncrementalDocService incrementalDocService) {
        this.docService = docService;
        this.incrementalDocService = incrementalDocService;
    }

    @GetMapping("/search")
    public Result<IPage<ComponentDoc>> searchDocs(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String framework,
            @RequestParam(required = false) String docType) {
        Page<ComponentDoc> pageObj = new Page<>(page, size);
        IPage<ComponentDoc> result = docService.searchDocs(q, framework, docType, pageObj);
        return Result.success(result);
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

    @PostMapping("/parse/incremental")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<Map<String, Object>> incrementalParse(
            @RequestParam Long versionId,
            @RequestParam String framework,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String gitRepoPath) {
        return Result.success(incrementalDocService.incrementalParseFiles(versionId, framework, files, gitRepoPath));
    }

    @PostMapping("/parse/full")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<Map<String, Object>> fullReparse(
            @RequestParam Long versionId,
            @RequestParam String framework,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String gitRepoPath) {
        return Result.success(incrementalDocService.fullReparse(versionId, framework, files, gitRepoPath));
    }

    @GetMapping("/parse/records/{versionId}")
    public Result<Map<String, Object>> getParseRecords(@PathVariable Long versionId) {
        return Result.success(incrementalDocService.getParseStatistics(versionId));
    }

    @GetMapping("/parse/records/{versionId}/detail")
    public Result<List<DocParseRecord>> getParseRecordDetails(@PathVariable Long versionId) {
        return Result.success(incrementalDocService.getParseRecordsByVersion(versionId));
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
