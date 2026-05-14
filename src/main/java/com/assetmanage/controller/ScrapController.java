package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.dto.AssetScrapRequest;
import com.assetmanage.entity.ScrapRecord;
import com.assetmanage.service.ScrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scrap")
@RequiredArgsConstructor
public class ScrapController {

    private final ScrapService scrapService;

    @PostMapping("/submit")
    public ApiResponse<String> submitScrap(@RequestBody AssetScrapRequest request) {
        String scrapId = scrapService.submitScrap(request);
        return ApiResponse.success(scrapId);
    }

    @GetMapping("/asset/{assetId}")
    public ApiResponse<List<ScrapRecord>> getScrapsByAsset(@PathVariable String assetId) {
        List<ScrapRecord> records = scrapService.getScrapsByAsset(assetId);
        return ApiResponse.success(records);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<ScrapRecord>> getScrapsByStatus(@PathVariable String status) {
        List<ScrapRecord> records = scrapService.getScrapsByStatus(status);
        return ApiResponse.success(records);
    }

    @GetMapping("/{scrapId}")
    public ApiResponse<ScrapRecord> getScrapById(@PathVariable String scrapId) {
        ScrapRecord record = scrapService.getScrapById(scrapId);
        return ApiResponse.success(record);
    }

    @GetMapping
    public ApiResponse<List<ScrapRecord>> getAllScraps() {
        List<ScrapRecord> records = scrapService.getAllScraps();
        return ApiResponse.success(records);
    }
}
