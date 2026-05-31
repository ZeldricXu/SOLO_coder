package com.chaoslab.modules.dns.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.modules.dns.dto.AsyncDnsResolveRequest;
import com.chaoslab.modules.dns.dto.AsyncDnsTaskResponse;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import com.chaoslab.modules.dns.service.DnsAsyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dns/async")
@RequiredArgsConstructor
public class DnsAsyncController {

    private final DnsAsyncService dnsAsyncService;

    @PostMapping("/resolve")
    public Mono<ApiResponse<AsyncDnsTaskResponse>> submitAsyncResolve(@RequestBody AsyncDnsResolveRequest request) {
        return dnsAsyncService.submitAsyncResolve(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/resolve/batch")
    public Flux<ApiResponse<AsyncDnsTaskResponse>> submitBatchResolve(@RequestBody List<AsyncDnsResolveRequest> requests) {
        return dnsAsyncService.submitBatchResolve(requests)
                .map(ApiResponse::success);
    }

    @GetMapping("/task/{taskId}")
    public Mono<ApiResponse<AsyncDnsTaskResponse>> getTaskStatus(@PathVariable String taskId) {
        return dnsAsyncService.getTaskStatus(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/task/{taskId}/result")
    public Mono<ApiResponse<DnsResolveResponse>> getTaskResult(@PathVariable String taskId) {
        return dnsAsyncService.getTaskResult(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/tasks")
    public Flux<ApiResponse<AsyncDnsTaskResponse>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return dnsAsyncService.listTasks(status, domain, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PostMapping("/task/{taskId}/cancel")
    public Mono<ApiResponse<Map<String, Object>>> cancelTask(@PathVariable String taskId) {
        return dnsAsyncService.cancelTask(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getAsyncStats() {
        return dnsAsyncService.getAsyncStats()
                .map(ApiResponse::success);
    }

    @PostMapping("/process")
    public Mono<ApiResponse<String>> processPendingTasks() {
        dnsAsyncService.processPendingTasks();
        return Mono.just(ApiResponse.success("Pending tasks processing triggered"));
    }
}
