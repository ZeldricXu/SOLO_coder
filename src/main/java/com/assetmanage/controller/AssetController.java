package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.dto.AssetRegisterRequest;
import com.assetmanage.dto.AssetUseRequest;
import com.assetmanage.dto.DepreciationData;
import com.assetmanage.dto.RegisterResponse;
import com.assetmanage.dto.UseResponse;
import com.assetmanage.entity.Asset;
import com.assetmanage.service.AssetService;
import com.assetmanage.service.DepreciationService;
import com.assetmanage.service.UsageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final UsageService usageService;
    private final DepreciationService depreciationService;

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> registerAsset(@Valid @RequestBody AssetRegisterRequest request) {
        String assetId = assetService.registerAsset(request);
        return ApiResponse.success(new RegisterResponse(assetId));
    }

    @PostMapping("/use")
    public ApiResponse<UseResponse> useAsset(@Valid @RequestBody AssetUseRequest request) {
        UseResponse response = usageService.useAsset(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/depreciation")
    public ApiResponse<DepreciationData> getDepreciation(
            @RequestParam String assetId,
            @RequestParam(required = false) String startPeriod,
            @RequestParam(required = false) String endPeriod) {
        DepreciationData data = depreciationService.getDepreciationByAssetAndPeriod(assetId, startPeriod, endPeriod);
        return ApiResponse.success(data);
    }

    @GetMapping("/{assetId}")
    public ApiResponse<Asset> getAssetById(@PathVariable String assetId) {
        Asset asset = assetService.getAssetById(assetId);
        return ApiResponse.success(asset);
    }

    @GetMapping
    public ApiResponse<List<Asset>> getAllAssets() {
        List<Asset> assets = assetService.getAllAssets();
        return ApiResponse.success(assets);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Asset>> getAssetsByStatus(@PathVariable String status) {
        List<Asset> assets = assetService.getAssetsByStatus(status);
        return ApiResponse.success(assets);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Asset>> getAssetsByType(@PathVariable String type) {
        List<Asset> assets = assetService.getAssetsByType(type);
        return ApiResponse.success(assets);
    }
}
