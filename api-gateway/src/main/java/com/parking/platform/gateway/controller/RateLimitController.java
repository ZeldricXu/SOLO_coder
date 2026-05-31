package com.parking.platform.gateway.controller;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.context.RequestContext;
import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.gateway.service.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rate-limit")
public class RateLimitController {

    private static final Logger log = LoggerFactory.getLogger(RateLimitController.class);

    private final RateLimitService rateLimitService;

    public RateLimitController(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        RequestContext ctx = RequestContext.current();
        String userId = ctx.getUserId();
        String key = userId != null ? "user:" + userId : "global";

        RateLimitService.RateLimitInfo info = rateLimitService.getRateLimitInfo(key);

        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("minuteLimit", info.minuteLimit());
        data.put("minuteRemaining", info.minuteRemaining());
        data.put("hourLimit", info.hourLimit());
        data.put("hourRemaining", info.hourRemaining());
        data.put("blocked", info.blocked());

        return ApiResponse.success(data);
    }

    @PostMapping("/block/{key}")
    public ApiResponse<Void> blockKey(@PathVariable String key, @RequestParam(defaultValue = "60000") long durationMs) {
        RequestContext ctx = RequestContext.current();
        if (!ctx.hasRole(Constants.ROLE_ADMIN)) {
            return ApiResponse.forbidden("Only admins can block keys");
        }

        log.info("Admin {} blocking key: {} for {}ms", ctx.getUserId(), key, durationMs);
        rateLimitService.blockKey(key, durationMs);
        return ApiResponse.success(null);
    }

    @PostMapping("/unblock/{key}")
    public ApiResponse<Void> unblockKey(@PathVariable String key) {
        RequestContext ctx = RequestContext.current();
        if (!ctx.hasRole(Constants.ROLE_ADMIN)) {
            return ApiResponse.forbidden("Only admins can unblock keys");
        }

        log.info("Admin {} unblocking key: {}", ctx.getUserId(), key);
        rateLimitService.unblockKey(key);
        return ApiResponse.success(null);
    }
}
