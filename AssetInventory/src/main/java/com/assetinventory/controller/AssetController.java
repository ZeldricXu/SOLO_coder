package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.entity.Asset;
import com.assetinventory.service.AssetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    @Autowired
    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Asset>> createAsset(@RequestBody Asset asset) {
        Asset created = assetService.createAsset(
                asset.getAssetName(),
                asset.getAssetCategory(),
                asset.getAssetQuantity(),
                asset.getAssetLocation(),
                asset.getAssetValue()
        );
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Asset>>> getAllAssets() {
        List<Asset> assets = assetService.getAllAssets();
        return ResponseEntity.ok(ApiResponse.success(assets));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Asset>>> getAssetsByStatus(@PathVariable String status) {
        List<Asset> assets = assetService.getAssetsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(assets));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<ApiResponse<List<Asset>>> getAssetsByCategory(@PathVariable String category) {
        List<Asset> assets = assetService.getAssetsByCategory(category);
        return ResponseEntity.ok(ApiResponse.success(assets));
    }

    @GetMapping("/{assetId}")
    public ResponseEntity<ApiResponse<Asset>> getAssetById(@PathVariable String assetId) {
        return assetService.getAssetById(assetId)
                .map(asset -> ResponseEntity.ok(ApiResponse.success(asset)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "资产不存在")));
    }
}
