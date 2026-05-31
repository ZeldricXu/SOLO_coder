package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.DnsRecord;
import com.solo.config.module.dns.DnsProxyService;
import com.solo.config.module.dns.plugin.DnsPluginManager;
import com.solo.config.module.dns.plugin.DnsResolverPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dns")
@RequiredArgsConstructor
public class DnsController {

    private final DnsProxyService dnsProxyService;
    private final DnsPluginManager pluginManager;

    @GetMapping("/resolve/{domain}")
    public Mono<Result<Map<String, Object>>> resolve(
            @PathVariable String domain,
            @RequestParam(defaultValue = "A") String recordType) {
        return dnsProxyService.resolveWithDetails(domain, recordType)
                .map(Result::success);
    }

    @GetMapping("/plugins")
    public Mono<Result<List<Map<String, Object>>>> listPlugins() {
        List<Map<String, Object>> plugins = pluginManager.getPlugins().stream()
                .map(this::toPluginInfo)
                .toList();
        return Mono.just(Result.success(plugins));
    }

    private Map<String, Object> toPluginInfo(DnsResolverPlugin plugin) {
        return Map.of(
                "name", plugin.getName(),
                "priority", plugin.getPriority(),
                "enabled", plugin.isEnabled()
        );
    }

    @GetMapping("/records")
    public Mono<Result<List<DnsRecord>>> listCachedRecords() {
        return dnsProxyService.listCachedRecords()
                .map(Result::success);
    }

    @PostMapping("/cache/refresh")
    public Mono<Result<Void>> refreshCache() {
        return dnsProxyService.refreshCache()
                .then(Mono.just(Result.success()));
    }
}
