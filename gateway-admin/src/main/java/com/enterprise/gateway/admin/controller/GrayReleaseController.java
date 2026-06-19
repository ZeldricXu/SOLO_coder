package com.enterprise.gateway.admin.controller;

import com.enterprise.gateway.admin.service.GrayReleaseService;
import com.enterprise.gateway.common.model.GrayReleaseRule;
import com.enterprise.gateway.common.model.TrafficMirrorRule;
import com.enterprise.gateway.common.model.UnifiedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/gray")
@RequiredArgsConstructor
public class GrayReleaseController {

    private final GrayReleaseService grayReleaseService;

    @GetMapping("/routes/{routeId}")
    public Mono<UnifiedResponse<GrayReleaseRule>> getGrayRuleByRouteId(@PathVariable String routeId) {
        return Mono.just(UnifiedResponse.success(grayReleaseService.getGrayRuleByRouteId(routeId)));
    }

    @PostMapping
    public Mono<UnifiedResponse<GrayReleaseRule>> createGrayRule(@RequestBody GrayReleaseRule rule) {
        return Mono.just(UnifiedResponse.success(grayReleaseService.createGrayRule(rule)));
    }

    @PutMapping("/{id}")
    public Mono<UnifiedResponse<GrayReleaseRule>> updateGrayRule(
            @PathVariable Long id,
            @RequestBody GrayReleaseRule rule) {
        rule.setId(id);
        return Mono.just(UnifiedResponse.success(grayReleaseService.updateGrayRule(rule)));
    }

    @DeleteMapping("/{id}")
    public Mono<UnifiedResponse<Void>> deleteGrayRule(@PathVariable Long id) {
        grayReleaseService.deleteGrayRule(id);
        return Mono.just(UnifiedResponse.success(null));
    }

    @PostMapping("/mirror")
    public Mono<UnifiedResponse<TrafficMirrorRule>> createMirrorRule(@RequestBody TrafficMirrorRule rule) {
        return Mono.just(UnifiedResponse.success(grayReleaseService.createMirrorRule(rule)));
    }

    @PutMapping("/mirror/{id}")
    public Mono<UnifiedResponse<TrafficMirrorRule>> updateMirrorRule(
            @PathVariable Long id,
            @RequestBody TrafficMirrorRule rule) {
        rule.setId(id);
        return Mono.just(UnifiedResponse.success(grayReleaseService.updateMirrorRule(rule)));
    }

    @DeleteMapping("/mirror/{id}")
    public Mono<UnifiedResponse<Void>> deleteMirrorRule(@PathVariable Long id) {
        grayReleaseService.deleteMirrorRule(id);
        return Mono.just(UnifiedResponse.success(null));
    }
}
