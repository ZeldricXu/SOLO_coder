package com.chaoslab.modules.dns.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.DnsResolutionPolicy;
import com.chaoslab.entity.DnsUpstream;
import com.chaoslab.modules.dns.dto.DnsResolveRequest;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import com.chaoslab.modules.dns.dto.ResolutionPolicyCreateRequest;
import com.chaoslab.modules.dns.dto.UpstreamCreateRequest;
import com.chaoslab.modules.dns.service.DnsProxyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dns")
@RequiredArgsConstructor
public class DnsProxyController {

    private final DnsProxyService dnsProxyService;

    @PostMapping("/upstreams")
    public Mono<ApiResponse<DnsUpstream>> createUpstream(
            @Valid @RequestBody UpstreamCreateRequest request) {
        return dnsProxyService.createUpstream(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/upstreams")
    public Mono<ApiResponse<List<DnsUpstream>>> listUpstreams(
            @RequestParam(required = false) String status) {
        return dnsProxyService.listUpstreams(status)
                .map(ApiResponse::success);
    }

    @PostMapping("/policies")
    public Mono<ApiResponse<DnsResolutionPolicy>> createPolicy(
            @Valid @RequestBody ResolutionPolicyCreateRequest request) {
        return dnsProxyService.createPolicy(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<List<DnsResolutionPolicy>>> listPolicies() {
        return dnsProxyService.listPolicies()
                .map(ApiResponse::success);
    }

    @PostMapping("/resolve")
    public Mono<ApiResponse<DnsResolveResponse>> resolve(
            @Valid @RequestBody DnsResolveRequest request) {
        return dnsProxyService.resolve(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/cache/stats")
    public Mono<ApiResponse<Map<String, Object>>> getCacheStats() {
        return dnsProxyService.getCacheStats()
                .map(ApiResponse::success);
    }
}
