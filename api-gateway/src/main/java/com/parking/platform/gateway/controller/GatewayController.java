package com.parking.platform.gateway.controller;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.context.RequestContext;
import com.parking.platform.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        RequestContext ctx = RequestContext.current();
        Map<String, Object> status = new HashMap<>();
        status.put("status", "ok");
        status.put("timestamp", Instant.now());
        status.put("requestId", ctx.getRequestId());
        status.put("userId", ctx.getUserId());
        status.put("elapsed", ctx.getElapsedMillis());
        return ApiResponse.success(status);
    }

    @PostMapping("/echo")
    public ApiResponse<Map<String, Object>> echo(@RequestBody(required = false) Map<String, Object> body,
                                                 @RequestHeader Map<String, String> headers) {
        Map<String, Object> result = new HashMap<>();
        result.put("body", body);
        result.put("headers", headers);
        result.put("timestamp", Instant.now());
        return ApiResponse.success(result);
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        RequestContext ctx = RequestContext.current();
        Map<String, Object> config = new HashMap<>();
        config.put("namespace", ctx.getNamespace());
        config.put("userId", ctx.getUserId());
        config.put("roles", ctx.getUserRoles());
        config.put("isAdmin", ctx.hasRole(Constants.ROLE_ADMIN));
        return ApiResponse.success(config);
    }
}
