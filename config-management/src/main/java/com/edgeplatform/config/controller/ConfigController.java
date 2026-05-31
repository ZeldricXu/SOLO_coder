package com.edgeplatform.config.controller;

import com.edgeplatform.common.dto.ApiResponse;
import com.edgeplatform.common.dto.PagedRequest;
import com.edgeplatform.common.dto.PagedResult;
import com.edgeplatform.config.dto.*;
import com.edgeplatform.config.entity.ConfigVersion;
import com.edgeplatform.config.service.ConfigManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigManagementService configManagementService;

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<ConfigResponse>>> createConfig(
            @RequestBody ConfigCreateRequest request) {
        return Mono.fromSupplier(() -> {
            ConfigResponse response = configManagementService.createConfig(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created(response));
        });
    }

    @GetMapping("/{configId}")
    public Mono<ResponseEntity<ApiResponse<ConfigResponse>>> getConfig(
            @PathVariable String configId) {
        return Mono.fromSupplier(() -> {
            ConfigResponse response = configManagementService.getConfig(configId);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @GetMapping("/namespace/{namespace}")
    public Mono<ResponseEntity<ApiResponse<ConfigResponse>>> getConfigByNamespace(
            @PathVariable String namespace) {
        return Mono.fromSupplier(() -> {
            ConfigResponse response = configManagementService.getConfigByNamespace(namespace);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<PagedResult<ConfigResponse>>>> listConfigs(
            @RequestParam(required = false) String namespace,
            @ModelAttribute PagedRequest request) {
        return Mono.fromSupplier(() -> {
            PagedResult<ConfigResponse> response = configManagementService.listConfigs(request, namespace);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @PutMapping("/{configId}")
    public Mono<ResponseEntity<ApiResponse<ConfigResponse>>> updateConfig(
            @PathVariable String configId,
            @RequestBody ConfigUpdateRequest request) {
        return Mono.fromSupplier(() -> {
            ConfigResponse response = configManagementService.updateConfig(configId, request);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @PostMapping("/{configId}/rollback")
    public Mono<ResponseEntity<ApiResponse<ConfigResponse>>> rollbackConfig(
            @PathVariable String configId,
            @RequestBody ConfigRollbackRequest request) {
        return Mono.fromSupplier(() -> {
            ConfigResponse response = configManagementService.rollbackConfig(configId, request);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @GetMapping("/{configId}/versions")
    public Mono<ResponseEntity<ApiResponse<List<ConfigVersion>>>> listConfigVersions(
            @PathVariable String configId) {
        return Mono.fromSupplier(() -> {
            List<ConfigVersion> response = configManagementService.listConfigVersions(configId);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @GetMapping("/{configId}/diff")
    public Mono<ResponseEntity<ApiResponse<ConfigDiffResponse>>> diffVersions(
            @PathVariable String configId,
            @RequestParam Integer fromVersion,
            @RequestParam Integer toVersion) {
        return Mono.fromSupplier(() -> {
            ConfigDiffResponse response = configManagementService.diffVersions(
                    configId, fromVersion, toVersion);
            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }

    @DeleteMapping("/{configId}")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteConfig(
            @PathVariable String configId) {
        return Mono.fromSupplier(() -> {
            configManagementService.deleteConfig(configId);
            return ResponseEntity.ok(ApiResponse.success(null));
        });
    }
}
