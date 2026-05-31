package com.meshcontrol.dns.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.response.ApiResponse;
import com.meshcontrol.common.response.PageResponse;
import com.meshcontrol.dns.dto.*;
import com.meshcontrol.dns.entity.DnsUpstream;
import com.meshcontrol.dns.entity.DnsZone;
import com.meshcontrol.dns.service.DnsProxyService;
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
    public Mono<ApiResponse<DnsUpstream>> addUpstream(@Valid @RequestBody UpstreamRequest request) {
        return Mono.just(ApiResponse.created(dnsProxyService.addUpstream(request)));
    }

    @GetMapping("/upstreams")
    public Mono<ApiResponse<PageResponse<DnsUpstream>>> listUpstreams(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<DnsUpstream> page = dnsProxyService.listUpstreams(pageNum, pageSize);
        return Mono.just(ApiResponse.success(PageResponse.of(page))));
    }

    @DeleteMapping("/upstreams/{upstreamId}")
    public Mono<ApiResponse<Boolean>> deleteUpstream(@PathVariable String upstreamId) {
        return Mono.just(ApiResponse.success(dnsProxyService.deleteUpstream(upstreamId)));
    }

    @PostMapping("/zones")
    public Mono<ApiResponse<DnsZone>> addZone(@Valid @RequestBody ZoneRequest request) {
        return Mono.just(ApiResponse.created(dnsProxyService.addZone(request)));
    }

    @GetMapping("/zones")
    public Mono<ApiResponse<List<DnsZone>>> listZones() {
        return Mono.just(ApiResponse.success(dnsProxyService.listZones()));
    }

    @DeleteMapping("/zones/{zoneId}")
    public Mono<ApiResponse<Boolean>> deleteZone(@PathVariable String zoneId) {
        return Mono.just(ApiResponse.success(dnsProxyService.deleteZone(zoneId)));
    }

    @PostMapping("/resolve")
    public Mono<ApiResponse<DnsQueryResponse> resolve(@Valid @RequestBody DnsQueryRequest request) {
        return Mono.just(ApiResponse.success(dnsProxyService.resolve(request)));
    }

    @GetMapping("/cache/stats")
    public Mono<ApiResponse<Map<String, Object>>> getCacheStats() {
        return Mono.just(ApiResponse.success(dnsProxyService.getCacheStats()));
    }
}
