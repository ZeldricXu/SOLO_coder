package com.designsystem.controller.api;

import com.designsystem.common.Result;
import com.designsystem.common.enums.ComponentFramework;
import com.designsystem.service.CodeGenerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/codegen")
public class CodeGenApiController {

    private final CodeGenerationService codeGenService;

    public CodeGenApiController(CodeGenerationService codeGenService) {
        this.codeGenService = codeGenService;
    }

    @PostMapping("/scaffold")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<Map<String, String>> generateScaffold(
            @RequestParam ComponentFramework framework,
            @RequestParam List<String> componentNames,
            @RequestParam String projectName,
            @RequestParam(defaultValue = "true") boolean includeTokens) throws Exception {
        return Result.success(codeGenService.generateScaffold(framework, componentNames, projectName, includeTokens));
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> downloadScaffold(@RequestBody Map<String, String> files) throws Exception {
        byte[] zip = codeGenService.downloadScaffold(files);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"scaffold.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    @PostMapping("/push")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<String> pushToGit(
            @RequestBody Map<String, Object> payload) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) payload.get("files");
        String gitUrl = (String) payload.get("gitUrl");
        String branch = (String) payload.getOrDefault("branch", "main");
        String username = (String) payload.get("username");
        String password = (String) payload.get("password");
        String commitMessage = (String) payload.get("commitMessage");

        return Result.success(codeGenService.pushToGitRepository(files, gitUrl, branch, username, password, commitMessage));
    }
}
