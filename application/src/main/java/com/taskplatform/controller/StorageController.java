package com.taskplatform.controller;

import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/backups")
    public ApiResponse<Map<String, Object>> createBackup(@RequestBody Map<String, Object> request) throws IOException {
        String sourcePath = (String) request.get("sourcePath");
        String backupType = (String) request.getOrDefault("backupType", "full");
        String createdBy = (String) request.getOrDefault("createdBy", "system");

        return ApiResponse.created(storageService.createBackup(sourcePath, backupType, createdBy));
    }

    @GetMapping("/backups")
    public ApiResponse<List<Map<String, Object>>> listBackups() throws IOException {
        return ApiResponse.success(storageService.listBackups());
    }

    @PostMapping("/backups/{backupId}/restore")
    public ApiResponse<Map<String, Object>> restoreBackup(
            @PathVariable String backupId,
            @RequestBody Map<String, String> request) throws IOException {
        String restorePath = request.getOrDefault("restorePath", "./restored");
        return ApiResponse.success(storageService.restoreBackup(backupId, restorePath));
    }

    @DeleteMapping("/backups/{backupId}")
    public ApiResponse<Void> deleteBackup(@PathVariable String backupId) throws IOException {
        storageService.deleteBackup(backupId);
        return ApiResponse.success(null);
    }
}
