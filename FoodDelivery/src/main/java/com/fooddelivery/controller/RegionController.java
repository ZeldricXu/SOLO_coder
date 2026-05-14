package com.fooddelivery.controller;

import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.entity.Region;
import com.fooddelivery.service.RegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/regions")
public class RegionController {

    @Autowired
    private RegionService regionService;

    @PostMapping
    public ResponseEntity<ApiResponse<Region>> createRegion(@RequestBody Region region) {
        Region saved = regionService.createRegion(region);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Region>>> getAllRegions() {
        List<Region> regions = regionService.getAllRegions();
        return ResponseEntity.ok(ApiResponse.success(regions));
    }

    @GetMapping("/{regionId}")
    public ResponseEntity<ApiResponse<Region>> getRegion(@PathVariable String regionId) {
        Optional<Region> region = regionService.getRegionById(regionId);
        if (region.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(region.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "区域不存在"));
    }

    @PutMapping("/{regionId}")
    public ResponseEntity<ApiResponse<Region>> updateRegion(@PathVariable String regionId,
                                                            @RequestBody Region region) {
        Region updated = regionService.updateRegion(regionId, region);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @DeleteMapping("/{regionId}")
    public ResponseEntity<ApiResponse<Void>> deleteRegion(@PathVariable String regionId) {
        regionService.deleteRegion(regionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
