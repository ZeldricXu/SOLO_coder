package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.SidecarInstance;
import com.solo.config.module.sidecar.SidecarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sidecars")
@RequiredArgsConstructor
public class SidecarController {

    private final SidecarService sidecarService;

    @PostMapping
    public Mono<Result<SidecarInstance>> injectSidecar(@RequestBody Map<String, Object> request) {
        String podName = (String) request.get("podName");
        String namespace = (String) request.getOrDefault("namespace", "default");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        return sidecarService.injectSidecar(podName, namespace, config)
                .map(Result::success);
    }

    @GetMapping
    public Flux<SidecarInstance> listInstances(@RequestParam(required = false) String namespace) {
        return sidecarService.listInstances(namespace);
    }

    @GetMapping("/{instanceId}")
    public Mono<Result<SidecarInstance>> getInstance(@PathVariable String instanceId) {
        return sidecarService.getInstance(instanceId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "Sidecar实例不存在"));
    }

    @PostMapping("/{instanceId}/config")
    public Mono<Result<SidecarInstance>> updateConfig(
            @PathVariable String instanceId,
            @RequestBody Map<String, Object> config) {
        return sidecarService.updateConfig(instanceId, config)
                .map(Result::success);
    }

    @PostMapping("/{instanceId}/heartbeat")
    public Mono<Result<Void>> heartbeat(@PathVariable String instanceId) {
        return sidecarService.heartbeat(instanceId)
                .then(Mono.just(Result.success()));
    }

    @DeleteMapping("/{instanceId}")
    public Mono<Result<Void>> removeSidecar(@PathVariable String instanceId) {
        return sidecarService.removeSidecar(instanceId)
                .then(Mono.just(Result.success()));
    }
}
