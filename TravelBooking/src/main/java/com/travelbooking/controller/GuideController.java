package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.Guide;
import com.travelbooking.service.GuideService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/guides")
@RequiredArgsConstructor
public class GuideController {

    private final GuideService guideService;

    @GetMapping
    public ApiResponse<List<Guide>> getAllGuides() {
        return ApiResponse.success(guideService.getAllGuides());
    }

    @GetMapping("/{id}")
    public ApiResponse<Guide> getGuideById(@PathVariable String id) {
        return guideService.getGuideById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "导游不存在"));
    }

    @GetMapping("/available")
    public ApiResponse<List<Guide>> getAvailableGuides() {
        return ApiResponse.success(guideService.getAvailableGuides());
    }

    @PostMapping
    public ApiResponse<Guide> createGuide(@RequestBody Guide guide) {
        Guide created = guideService.createGuide(guide);
        return ApiResponse.success(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Guide> updateGuide(@PathVariable String id, @RequestBody Guide guide) {
        Guide updated = guideService.updateGuide(id, guide);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteGuide(@PathVariable String id) {
        guideService.deleteGuide(id);
        return ApiResponse.success(null);
    }
}
