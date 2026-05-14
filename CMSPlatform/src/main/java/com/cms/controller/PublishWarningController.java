package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.PublishWarning;
import com.cms.service.PublishWarningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/publish-warnings")
public class PublishWarningController {

    @Autowired
    private PublishWarningService publishWarningService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PublishWarning>>> getWarningsByPublisherId(
            @RequestParam(required = false) String publisherId) {
        List<PublishWarning> warnings;
        if (publisherId != null) {
            warnings = publishWarningService.getWarningsByPublisherId(publisherId);
        } else {
            warnings = publishWarningService.getPendingWarningsToProcess();
        }
        return ResponseEntity.ok(ApiResponse.success(warnings));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<PublishWarning>>> getPendingWarnings(
            @RequestParam String publisherId) {
        List<PublishWarning> warnings = publishWarningService.getPendingWarningsByPublisherId(publisherId);
        return ResponseEntity.ok(ApiResponse.success(warnings));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> getPendingCount(@RequestParam String publisherId) {
        long count = publishWarningService.countPendingWarningsByPublisherId(publisherId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @GetMapping("/content/{contentId}")
    public ResponseEntity<ApiResponse<List<PublishWarning>>> getWarningsByContentId(
            @PathVariable String contentId) {
        List<PublishWarning> warnings = publishWarningService.getWarningsByContentId(contentId);
        return ResponseEntity.ok(ApiResponse.success(warnings));
    }

    @GetMapping("/{warningId}")
    public ResponseEntity<ApiResponse<PublishWarning>> getWarningById(@PathVariable String warningId) {
        try {
            PublishWarning warning = publishWarningService.getWarningById(warningId);
            return ResponseEntity.ok(ApiResponse.success(warning));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{warningId}/acknowledge")
    public ResponseEntity<ApiResponse<Void>> acknowledgeWarning(
            @PathVariable String warningId,
            @RequestParam String acknowledgedById,
            @RequestParam(required = false) String acknowledgedByName) {
        try {
            publishWarningService.acknowledgeWarning(
                warningId, 
                acknowledgedById, 
                acknowledgedByName != null ? acknowledgedByName : "用户");
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/acknowledge-all")
    public ResponseEntity<ApiResponse<Void>> acknowledgeAllWarnings(
            @RequestParam String publisherId,
            @RequestParam String acknowledgedById,
            @RequestParam(required = false) String acknowledgedByName) {
        try {
            publishWarningService.acknowledgeAllWarningsByPublisher(
                publisherId, 
                acknowledgedById, 
                acknowledgedByName != null ? acknowledgedByName : "用户");
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/content/{contentId}")
    public ResponseEntity<ApiResponse<Void>> cancelWarningsByContentId(@PathVariable String contentId) {
        try {
            publishWarningService.cancelWarningsByContentId(contentId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/importance/{importanceLevel}")
    public ResponseEntity<ApiResponse<List<PublishWarning>>> getWarningsByImportanceLevel(
            @PathVariable String importanceLevel) {
        List<PublishWarning> warnings = publishWarningService.getWarningsByImportanceLevel(importanceLevel);
        return ResponseEntity.ok(ApiResponse.success(warnings));
    }
}
