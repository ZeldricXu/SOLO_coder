package com.meshcontrol.image.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.response.ApiResponse;
import com.meshcontrol.common.response.PageResponse;
import com.meshcontrol.image.dto.ImagePullRequest;
import com.meshcontrol.image.dto.ImageSyncRequest;
import com.meshcontrol.image.dto.RegistryRequest;
import com.meshcontrol.image.entity.ImageManifest;
import com.meshcontrol.image.entity.ImageRegistry;
import com.meshcontrol.image.entity.ImageRepository;
import com.meshcontrol.image.entity.ImageSyncTask;
import com.meshcontrol.image.service.ImageDistributionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageDistributionController {

    private final ImageDistributionService imageDistributionService;

    @PostMapping("/registries")
    public Mono<ApiResponse<ImageRegistry>> addRegistry(@Valid @RequestBody RegistryRequest request) {
        return Mono.just(ApiResponse.created(imageDistributionService.addRegistry(request)));
    }

    @GetMapping("/registries")
    public Mono<ApiResponse<PageResponse<ImageRegistry>>> listRegistries(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<ImageRegistry> page = imageDistributionService.listRegistries(pageNum, pageSize);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @GetMapping("/registries/{registryId}")
    public Mono<ApiResponse<ImageRegistry>> getRegistry(@PathVariable String registryId) {
        return Mono.just(ApiResponse.success(imageDistributionService.getRegistry(registryId)));
    }

    @DeleteMapping("/registries/{registryId}")
    public Mono<ApiResponse<Boolean>> deleteRegistry(@PathVariable String registryId) {
        return Mono.just(ApiResponse.success(imageDistributionService.deleteRegistry(registryId)));
    }

    @PostMapping("/registries/{registryId}/repositories")
    public Mono<ApiResponse<ImageRepository>> addRepository(
            @PathVariable String registryId,
            @RequestParam String name,
            @RequestParam(required = false) String description) {
        return Mono.just(ApiResponse.created(
                imageDistributionService.addRepository(registryId, name, description)));
    }

    @GetMapping("/repositories")
    public Mono<ApiResponse<List<ImageRepository>>> listRepositories(
            @RequestParam(required = false) String registryId) {
        return Mono.just(ApiResponse.success(imageDistributionService.listRepositories(registryId)));
    }

    @GetMapping("/manifests")
    public Mono<ApiResponse<PageResponse<ImageManifest>>> listManifests(
            @RequestParam(required = false) String repoId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<ImageManifest> page = imageDistributionService.listManifests(repoId, pageNum, pageSize);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @PostMapping("/pull")
    public Mono<ApiResponse<Map<String, Object>>> pullImage(@Valid @RequestBody ImagePullRequest request) {
        return Mono.just(ApiResponse.success(imageDistributionService.pullImage(request)));
    }

    @PostMapping("/manifests/{manifestId}/p2p")
    public Mono<ApiResponse<Map<String, Object>>> enableP2p(
            @PathVariable String manifestId,
            @RequestBody List<String> seedNodes) {
        return Mono.just(ApiResponse.success(imageDistributionService.enableP2p(manifestId, seedNodes)));
    }

    @PostMapping("/sync")
    public Mono<ApiResponse<ImageSyncTask>> startSync(@Valid @RequestBody ImageSyncRequest request) {
        return Mono.just(ApiResponse.created(imageDistributionService.startSync(request)));
    }

    @GetMapping("/sync/{taskId}")
    public Mono<ApiResponse<ImageSyncTask>> getSyncTask(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(imageDistributionService.getSyncTask(taskId)));
    }

    @GetMapping("/sync")
    public Mono<ApiResponse<List<ImageSyncTask>>> listSyncTasks(
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(imageDistributionService.listSyncTasks(status)));
    }

    @PostMapping("/sync/{taskId}/cancel")
    public Mono<ApiResponse<Boolean>> cancelSync(@PathVariable String taskId) {
        return Mono.just(ApiResponse.success(imageDistributionService.cancelSync(taskId)));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getDistributionStats() {
        return Mono.just(ApiResponse.success(imageDistributionService.getDistributionStats()));
    }
}
