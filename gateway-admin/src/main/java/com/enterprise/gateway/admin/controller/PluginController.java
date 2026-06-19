package com.enterprise.gateway.admin.controller;

import com.enterprise.gateway.admin.service.PluginService;
import com.enterprise.gateway.common.model.PluginConfig;
import com.enterprise.gateway.common.model.UnifiedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/admin/plugins")
@RequiredArgsConstructor
public class PluginController {

    private final PluginService pluginService;

    @GetMapping
    public Mono<UnifiedResponse<List<PluginConfig>>> listPlugins(
            @RequestParam(required = false) String pluginType,
            @RequestParam(required = false) String routeId) {
        return Mono.just(UnifiedResponse.success(pluginService.listPlugins(pluginType, routeId)));
    }

    @GetMapping("/{id}")
    public Mono<UnifiedResponse<PluginConfig>> getPluginById(@PathVariable Long id) {
        return Mono.just(UnifiedResponse.success(pluginService.getPluginById(id)));
    }

    @PostMapping
    public Mono<UnifiedResponse<PluginConfig>> createPlugin(@RequestBody PluginConfig config) {
        return Mono.just(UnifiedResponse.success(pluginService.createPlugin(config)));
    }

    @PutMapping("/{id}")
    public Mono<UnifiedResponse<PluginConfig>> updatePlugin(
            @PathVariable Long id,
            @RequestBody PluginConfig config) {
        config.setId(id);
        return Mono.just(UnifiedResponse.success(pluginService.updatePlugin(config)));
    }

    @DeleteMapping("/{id}")
    public Mono<UnifiedResponse<Void>> deletePlugin(@PathVariable Long id) {
        pluginService.deletePlugin(id);
        return Mono.just(UnifiedResponse.success(null));
    }

    @PostMapping("/toggle/{id}")
    public Mono<UnifiedResponse<PluginConfig>> togglePlugin(@PathVariable Long id) {
        return Mono.just(UnifiedResponse.success(pluginService.togglePlugin(id)));
    }
}
