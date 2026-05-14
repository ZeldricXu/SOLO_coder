package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.Tourist;
import com.travelbooking.service.TouristService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tourists")
@RequiredArgsConstructor
public class TouristController {

    private final TouristService touristService;

    @GetMapping
    public ApiResponse<List<Tourist>> getAllTourists() {
        return ApiResponse.success(touristService.getAllTourists());
    }

    @GetMapping("/{id}")
    public ApiResponse<Tourist> getTouristById(@PathVariable String id) {
        return touristService.getTouristById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "游客不存在"));
    }

    @PostMapping
    public ApiResponse<Tourist> createTourist(@RequestBody Tourist tourist) {
        Tourist created = touristService.createTourist(tourist);
        return ApiResponse.success(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Tourist> updateTourist(@PathVariable String id, @RequestBody Tourist tourist) {
        Tourist updated = touristService.updateTourist(id, tourist);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTourist(@PathVariable String id) {
        touristService.deleteTourist(id);
        return ApiResponse.success(null);
    }
}
