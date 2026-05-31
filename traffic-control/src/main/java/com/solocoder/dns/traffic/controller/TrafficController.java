package com.solocoder.dns.traffic.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.traffic.model.*;
import com.solocoder.dns.traffic.service.TrafficStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/traffic")
@RequiredArgsConstructor
public class TrafficController {
    private final TrafficStrategyService strategyService;

    @PostMapping("/strategies")
    public ApiResponse<TrafficStrategy> create(@RequestBody TrafficStrategy strategy) {
        return ApiResponse.success(201, strategyService.createStrategy(strategy));
    }

    @GetMapping("/strategies")
    public ApiResponse<PageResult<TrafficStrategy>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type) {
        return ApiResponse.success(strategyService.listStrategies(page, size, type));
    }

    @GetMapping("/strategies/{id}")
    public ApiResponse<TrafficStrategy> get(@PathVariable String id) {
        return ApiResponse.success(strategyService.getStrategy(id));
    }

    @PutMapping("/strategies/{id}")
    public ApiResponse<TrafficStrategy> update(@PathVariable String id, @RequestBody TrafficStrategy strategy) {
        strategy.setStrategyId(id);
        return ApiResponse.success(strategyService.updateStrategy(strategy));
    }

    @DeleteMapping("/strategies/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        strategyService.deleteStrategy(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/canary/check")
    public ApiResponse<Map<String, Object>> checkCanary(@RequestBody Map<String, Object> request) {
        CanaryConfig config = new CanaryConfig();
        config.setTrafficPercent((Integer) request.get("trafficPercent"));
        boolean result = strategyService.shouldRouteToCanary(
                (String) request.get("clientId"),
                (String) request.get("headerValue"),
                config
        );
        return ApiResponse.success(Map.of("routeToCanary", result));
    }

    @PostMapping("/bluegreen/select")
    public ApiResponse<Map<String, Object>> selectBlueGreen(@RequestBody BlueGreenConfig config) {
        String version = strategyService.selectBlueGreenVersion(config);
        return ApiResponse.success(Map.of("selectedVersion", version));
    }

    @PostMapping("/mirror/check")
    public ApiResponse<Map<String, Object>> checkMirror(@RequestBody TrafficMirrorConfig config) {
        boolean result = strategyService.shouldMirrorRequest(config);
        return ApiResponse.success(Map.of("shouldMirror", result));
    }
}
