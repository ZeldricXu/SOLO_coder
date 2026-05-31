package com.observability.profiling.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.profiling.model.ProfileResult;
import com.observability.profiling.service.ProfilingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/profiling")
@RequiredArgsConstructor
public class ProfilingController {

    private final ProfilingService profilingService;

    @PostMapping("/cpu/start")
    public Mono<ApiResponse<ProfileResult>> startCPUProfile(
            @RequestParam(defaultValue = "10000") int durationMs,
            @RequestParam(defaultValue = "100") int intervalMs) {
        return profilingService.startCPUProfile(durationMs, intervalMs)
                .map(ApiResponse::success);
    }

    @GetMapping("/{profileId}")
    public Mono<ApiResponse<ProfileResult>> getProfile(@PathVariable String profileId) {
        return profilingService.getProfile(profileId)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<List<ProfileResult>>> listProfiles() {
        return profilingService.listProfiles()
                .map(ApiResponse::success);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getSystemStats() {
        return profilingService.getSystemStats()
                .map(ApiResponse::success);
    }

    @GetMapping("/compare/{profileId1}/{profileId2}")
    public Mono<ApiResponse<ProfileResult>> compareProfiles(
            @PathVariable String profileId1,
            @PathVariable String profileId2) {
        return profilingService.compareProfiles(profileId1, profileId2)
                .map(ApiResponse::success);
    }
}
