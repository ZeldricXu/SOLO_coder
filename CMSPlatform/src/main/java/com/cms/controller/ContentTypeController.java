package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.ContentTypeConfig;
import com.cms.service.ContentTypeConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/content-types")
public class ContentTypeController {

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @PostMapping
    public ResponseEntity<ApiResponse<ContentTypeConfig>> createConfig(@RequestBody ContentTypeConfig config) {
        try {
            ContentTypeConfig created = contentTypeConfigService.createConfig(config);
            return ResponseEntity.ok(ApiResponse.success(created));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<ContentTypeConfig>> updateConfig(
            @PathVariable String typeCode, 
            @RequestBody ContentTypeConfig config) {
        try {
            ContentTypeConfig updated = contentTypeConfigService.updateConfig(typeCode, config);
            return ResponseEntity.ok(ApiResponse.success(updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<Void>> deleteConfig(@PathVariable String typeCode) {
        try {
            contentTypeConfigService.deleteConfig(typeCode);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{typeCode}/activate")
    public ResponseEntity<ApiResponse<ContentTypeConfig>> activateConfig(@PathVariable String typeCode) {
        try {
            ContentTypeConfig config = contentTypeConfigService.activateConfig(typeCode);
            return ResponseEntity.ok(ApiResponse.success(config));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{typeCode}/deactivate")
    public ResponseEntity<ApiResponse<ContentTypeConfig>> deactivateConfig(@PathVariable String typeCode) {
        try {
            ContentTypeConfig config = contentTypeConfigService.deactivateConfig(typeCode);
            return ResponseEntity.ok(ApiResponse.success(config));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ContentTypeConfig>>> getAllConfigs(
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly) {
        List<ContentTypeConfig> configs = activeOnly 
            ? contentTypeConfigService.getAllActiveConfigs() 
            : contentTypeConfigService.getAllConfigs();
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<ContentTypeConfig>> getConfigByCode(@PathVariable String typeCode) {
        try {
            ContentTypeConfig config = contentTypeConfigService.getConfigByCode(typeCode);
            return ResponseEntity.ok(ApiResponse.success(config));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/codes")
    public ResponseEntity<ApiResponse<List<String>>> getActiveTypeCodes() {
        List<String> typeCodes = contentTypeConfigService.getActiveTypeCodes();
        return ResponseEntity.ok(ApiResponse.success(typeCodes));
    }

    @GetMapping("/urgency/{urgencyLevel}")
    public ResponseEntity<ApiResponse<List<ContentTypeConfig>>> getConfigsByUrgencyLevel(
            @PathVariable String urgencyLevel) {
        List<ContentTypeConfig> configs = contentTypeConfigService.getConfigsByUrgencyLevel(urgencyLevel);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/importance/{importanceLevel}")
    public ResponseEntity<ApiResponse<List<ContentTypeConfig>>> getConfigsByImportanceLevel(
            @PathVariable String importanceLevel) {
        List<ContentTypeConfig> configs = contentTypeConfigService.getConfigsByImportanceLevel(importanceLevel);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }
}
