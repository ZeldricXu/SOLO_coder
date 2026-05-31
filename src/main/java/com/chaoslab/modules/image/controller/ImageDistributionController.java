package com.chaoslab.modules.image.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.ImageLayer;
import com.chaoslab.entity.ImageRepository;
import com.chaoslab.entity.ImageSyncTask;
import com.chaoslab.modules.image.dto.ImagePullResponse;
import com.chaoslab.modules.image.dto.ImageSyncRequest;
import com.chaoslab.modules.image.dto.RepositoryCreateRequest;
import com.chaoslab.modules.image.service.ImageDistributionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/image")
@RequiredArgsConstructor
public class ImageDistributionController {

    private final ImageDistributionService imageDistributionService;

    @PostMapping("/repositories")
    public Mono<ApiResponse<ImageRepository>> createRepository(
            @Valid @RequestBody RepositoryCreateRequest request) {
        return imageDistributionService.createRepository(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/repositories")
    public Mono<ApiResponse<List<ImageRepository>>> listRepositories(
            @RequestParam(required = false) String status) {
        return imageDistributionService.listRepositories(status)
                .map(ApiResponse::success);
    }

    @GetMapping("/repositories/{repoId}")
    public Mono<ApiResponse<ImageRepository>> getRepository(@PathVariable String repoId) {
        return imageDistributionService.getRepository(repoId)
                .map(ApiResponse::success);
    }

    @PostMapping("/sync")
    public Mono<ApiResponse<ImageSyncTask>> createSyncTask(
            @Valid @RequestBody ImageSyncRequest request) {
        return imageDistributionService.createSyncTask(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/sync/{taskId}")
    public Mono<ApiResponse<ImageSyncTask>> getSyncTask(@PathVariable String taskId) {
        return imageDistributionService.getSyncTask(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/sync")
    public Mono<ApiResponse<List<ImageSyncTask>>> listSyncTasks(
            @RequestParam(required = false) String status) {
        return imageDistributionService.listSyncTasks(status)
                .map(ApiResponse::success);
    }

    @PostMapping("/pull")
    public Mono<ApiResponse<ImagePullResponse>> pullImage(
            @RequestBody Map<String, Object> request) {
        String repoId = (String) request.get("repoId");
        String imageReference = (String) request.get("imageReference");
        boolean useP2p = (Boolean) request.getOrDefault("useP2p", false);
        return imageDistributionService.pullImage(repoId, imageReference, useP2p)
                .map(ApiResponse::success);
    }

    @GetMapping("/pull/{pullId}")
    public Mono<ApiResponse<ImagePullResponse>> getPullProgress(@PathVariable String pullId) {
        return imageDistributionService.getPullProgress(pullId)
                .map(ApiResponse::success);
    }

    @GetMapping("/layers")
    public Flux<ImageLayer> listLayers(@RequestParam String repoId) {
        return imageDistributionService.listLayers(repoId);
    }

    @GetMapping("/p2p/stats")
    public Mono<ApiResponse<Map<String, Object>>> getP2pStats() {
        return imageDistributionService.getP2pStats()
                .map(ApiResponse::success);
    }
}
