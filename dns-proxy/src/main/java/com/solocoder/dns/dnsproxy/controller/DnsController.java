package com.solocoder.dns.dnsproxy.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.dnsproxy.model.DnsResolveRequest;
import com.solocoder.dns.dnsproxy.model.DnsResolveResponse;
import com.solocoder.dns.dnsproxy.model.DnsUpstream;
import com.solocoder.dns.dnsproxy.service.DnsCacheService;
import com.solocoder.dns.dnsproxy.service.DnsResolveService;
import com.solocoder.dns.dnsproxy.service.DnsUpstreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dns")
@RequiredArgsConstructor
public class DnsController {
    private final DnsResolveService resolveService;
    private final DnsUpstreamService upstreamService;
    private final DnsCacheService cacheService;

    @PostMapping("/resolve")
    public Mono<ApiResponse<DnsResolveResponse>> resolve(@RequestBody DnsResolveRequest request) {
        return resolveService.resolve(request).map(ApiResponse::success);
    }

    @PostMapping("/upstream")
    public ApiResponse<DnsUpstream> createUpstream(@RequestBody DnsUpstream upstream) {
        return ApiResponse.success(201, upstreamService.createUpstream(upstream));
    }

    @GetMapping("/upstream")
    public ApiResponse<List<DnsUpstream>> listUpstreams() {
        return ApiResponse.success(upstreamService.getAllUpstreams());
    }

    @GetMapping("/upstream/{id}")
    public ApiResponse<DnsUpstream> getUpstream(@PathVariable String id) {
        return ApiResponse.success(upstreamService.getUpstream(id));
    }

    @PutMapping("/upstream/{id}")
    public ApiResponse<DnsUpstream> updateUpstream(@PathVariable String id, @RequestBody DnsUpstream upstream) {
        upstream.setId(id);
        return ApiResponse.success(upstreamService.updateUpstream(upstream));
    }

    @DeleteMapping("/upstream/{id}")
    public ApiResponse<Void> deleteUpstream(@PathVariable String id) {
        upstreamService.deleteUpstream(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/cache/{domain}")
    public ApiResponse<Void> invalidateCache(@PathVariable String domain,
                                             @RequestParam(defaultValue = "1") Integer type) {
        cacheService.invalidateCache(domain, type);
        return ApiResponse.success(null);
    }

    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> getCacheStats() {
        return ApiResponse.success(Map.of("size", cacheService.getCacheSize()));
    }
}
