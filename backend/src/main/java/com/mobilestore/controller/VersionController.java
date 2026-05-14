package com.mobilestore.controller;

import com.mobilestore.common.ApiResponse;
import com.mobilestore.dto.ApprovalRequest;
import com.mobilestore.dto.VersionPublishRequest;
import com.mobilestore.entity.ApprovalLog;
import com.mobilestore.entity.Version;
import com.mobilestore.service.VersionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/apps")
@CrossOrigin(origins = "*")
public class VersionController {

    @Autowired
    private VersionService versionService;

    @PostMapping("/publish")
    public ApiResponse<Map<String, Object>> publishVersion(@Valid @RequestBody VersionPublishRequest request) {
        Map<String, Object> result = versionService.publishVersion(request);
        return ApiResponse.success("版本提交成功，已通知审批人员", result);
    }

    @PostMapping("/approve")
    public ApiResponse<Map<String, Object>> processApproval(@Valid @RequestBody ApprovalRequest request) {
        Map<String, Object> result = versionService.processApproval(
                request.getVersionId(),
                request.getResult(),
                request.getComment(),
                request.getApprover()
        );
        return ApiResponse.success("审批处理完成", result);
    }

    @GetMapping("/approve/permission")
    public ApiResponse<Map<String, Object>> checkApprovalPermission(
            @RequestParam(required = false) String userId) {
        Map<String, Object> result = versionService.checkApprovalPermission(
                userId != null ? userId : "reviewer_001"
        );
        return ApiResponse.success(result);
    }

    @GetMapping("/versions")
    public ApiResponse<List<Version>> getVersions(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String status) {
        List<Version> versions = versionService.getVersions(appId, status);
        return ApiResponse.success(versions);
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<Version> getVersion(@PathVariable String versionId) {
        Version version = versionService.getVersion(versionId);
        return ApiResponse.success(version);
    }

    @GetMapping("/versions/{versionId}/logs")
    public ApiResponse<List<ApprovalLog>> getApprovalLogs(@PathVariable String versionId) {
        List<ApprovalLog> logs = versionService.getApprovalLogs(versionId);
        return ApiResponse.success(logs);
    }
}
