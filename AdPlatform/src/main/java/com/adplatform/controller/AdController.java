package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.dto.CreateAdRequest;
import com.adplatform.dto.CreateAdResponse;
import com.adplatform.entity.AdInfo;
import com.adplatform.service.AdManagementService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ads")
public class AdController {
    private final AdManagementService adManagementService;

    public AdController(AdManagementService adManagementService) {
        this.adManagementService = adManagementService;
    }

    @PostMapping("/create")
    public ApiResponse<CreateAdResponse> createAd(@RequestBody CreateAdRequest request) {
        CreateAdResponse response = adManagementService.createAd(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/review")
    public ApiResponse<AdInfo> reviewAd(
            @RequestParam String adId,
            @RequestParam boolean approved,
            @RequestParam(required = false) String reason) {
        AdInfo adInfo = adManagementService.reviewAd(adId, approved, reason);
        return ApiResponse.success(adInfo);
    }

    @GetMapping("/{adId}")
    public ApiResponse<Optional<AdInfo>> getAdById(@PathVariable String adId) {
        Optional<AdInfo> adInfo = adManagementService.getAdById(adId);
        return ApiResponse.success(adInfo);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<AdInfo>> getAdsByStatus(@PathVariable String status) {
        List<AdInfo> ads = adManagementService.getAdsByStatus(status);
        return ApiResponse.success(ads);
    }

    @GetMapping("/advertiser/{advertiser}")
    public ApiResponse<List<AdInfo>> getAdsByAdvertiser(@PathVariable String advertiser) {
        List<AdInfo> ads = adManagementService.getAdsByAdvertiser(advertiser);
        return ApiResponse.success(ads);
    }
}
