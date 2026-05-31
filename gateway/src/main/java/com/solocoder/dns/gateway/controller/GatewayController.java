package com.solocoder.dns.gateway.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.gateway.model.RequestLog;
import com.solocoder.dns.gateway.service.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
public class GatewayController {
    private final TraceService traceService;

    @GetMapping("/traces/{traceId}")
    public ApiResponse<RequestLog> getTrace(@PathVariable String traceId) {
        return ApiResponse.success(traceService.getTrace(traceId));
    }

    @GetMapping("/traces")
    public ApiResponse<PageResult<RequestLog>> listTraces(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String serviceName) {
        return ApiResponse.success(traceService.listTraces(page, size, serviceName));
    }
}
