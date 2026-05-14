package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.dto.DepreciationData;
import com.assetmanage.entity.DepreciationRecord;
import com.assetmanage.service.DepreciationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/depreciation")
@RequiredArgsConstructor
public class DepreciationController {

    private final DepreciationService depreciationService;

    @PostMapping("/calculate")
    public ApiResponse<Void> calculateDepreciation(@RequestParam(defaultValue = "false") boolean fullCalculation) {
        depreciationService.calculateDepreciation(fullCalculation);
        return ApiResponse.success();
    }

    @PostMapping("/pre-calculate")
    public ApiResponse<Map<String, Object>> preCalculateNextPeriod() {
        depreciationService.preCalculateNextPeriod();
        Map<String, Object> result = new HashMap<>();
        result.put("cacheSize", depreciationService.getCacheSize());
        result.put("message", "预计算完成");
        return ApiResponse.success(result);
    }

    @GetMapping("/methods")
    public ApiResponse<Map<String, String>> getAvailableMethods() {
        Map<String, String> methods = depreciationService.getAvailableDepreciationMethods();
        return ApiResponse.success(methods);
    }

    @GetMapping("/methods/all")
    public ApiResponse<List<String>> getAllMethods() {
        List<String> methods = depreciationService.getAllMethodCodes();
        return ApiResponse.success(methods);
    }

    @GetMapping("/methods/{methodCode}/enabled")
    public ApiResponse<Map<String, Object>> checkMethodEnabled(@PathVariable String methodCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("methodCode", methodCode);
        result.put("supported", depreciationService.isMethodSupported(methodCode));
        result.put("enabled", depreciationService.isMethodEnabled(methodCode));
        return ApiResponse.success(result);
    }

    @GetMapping("/cache/status")
    public ApiResponse<Map<String, Object>> getCacheStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("cacheSize", depreciationService.getCacheSize());
        return ApiResponse.success(result);
    }

    @PostMapping("/cache/clear")
    public ApiResponse<Void> clearCache() {
        depreciationService.clearCache();
        return ApiResponse.success();
    }

    @GetMapping("/asset/{assetId}")
    public ApiResponse<List<DepreciationRecord>> getDepreciationByAsset(@PathVariable String assetId) {
        List<DepreciationRecord> records = depreciationService.getDepreciationRecordsByAsset(assetId);
        return ApiResponse.success(records);
    }

    @GetMapping("/period/{period}")
    public ApiResponse<List<DepreciationRecord>> getDepreciationByPeriod(@PathVariable String period) {
        List<DepreciationRecord> records = depreciationService.getDepreciationByPeriod(period);
        return ApiResponse.success(records);
    }

    @GetMapping("/period/{period}/total")
    public ApiResponse<Map<String, Object>> getTotalDepreciationForPeriod(@PathVariable String period) {
        java.math.BigDecimal total = depreciationService.getTotalDepreciationForPeriod(period);
        Map<String, Object> result = new HashMap<>();
        result.put("period", period);
        result.put("totalDepreciation", total);
        return ApiResponse.success(result);
    }

    @GetMapping("/query")
    public ApiResponse<DepreciationData> queryDepreciation(
            @RequestParam String assetId,
            @RequestParam(required = false) String startPeriod,
            @RequestParam(required = false) String endPeriod) {
        DepreciationData data = depreciationService.getDepreciationByAssetAndPeriod(assetId, startPeriod, endPeriod);
        return ApiResponse.success(data);
    }

    @GetMapping
    public ApiResponse<List<DepreciationRecord>> getAllDepreciation() {
        List<DepreciationRecord> records = depreciationService.getAllDepreciationRecords();
        return ApiResponse.success(records);
    }
}
