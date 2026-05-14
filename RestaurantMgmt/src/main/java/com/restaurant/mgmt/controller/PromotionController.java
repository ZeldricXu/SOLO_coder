package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.model.Promotion;
import com.restaurant.mgmt.service.PromotionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {

    @Autowired
    private PromotionService promotionService;

    @PostMapping
    public ApiResponse<Promotion> createPromotion(@RequestBody Promotion promotion) {
        Promotion saved = promotionService.createPromotion(promotion);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<Promotion>> getAllPromotions() {
        List<Promotion> promotions = promotionService.getAllPromotions();
        return ApiResponse.success(promotions);
    }

    @GetMapping("/{promotionId}")
    public ApiResponse<Promotion> getPromotion(@PathVariable String promotionId) {
        Promotion promotion = promotionService.getPromotionById(promotionId);
        return ApiResponse.success(promotion);
    }

    @GetMapping("/active")
    public ApiResponse<List<Promotion>> getActivePromotions() {
        List<Promotion> promotions = promotionService.getActivePromotions();
        return ApiResponse.success(promotions);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Promotion>> getPromotionsByType(@PathVariable String type) {
        List<Promotion> promotions = promotionService.getPromotionsByType(type);
        return ApiResponse.success(promotions);
    }

    @PutMapping("/{promotionId}")
    public ApiResponse<Promotion> updatePromotion(
            @PathVariable String promotionId,
            @RequestBody Promotion promotion) {
        Promotion updated = promotionService.updatePromotion(promotionId, promotion);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{promotionId}")
    public ApiResponse<Void> deletePromotion(@PathVariable String promotionId) {
        promotionService.deletePromotion(promotionId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{promotionId}/activate")
    public ApiResponse<Promotion> activatePromotion(@PathVariable String promotionId) {
        Promotion promotion = promotionService.activatePromotion(promotionId);
        return ApiResponse.success(promotion);
    }

    @PostMapping("/{promotionId}/deactivate")
    public ApiResponse<Promotion> deactivatePromotion(@PathVariable String promotionId) {
        Promotion promotion = promotionService.deactivatePromotion(promotionId);
        return ApiResponse.success(promotion);
    }

    @PostMapping("/{promotionId}/calculate")
    public ApiResponse<Map<String, Object>> calculateDiscount(
            @PathVariable String promotionId,
            @RequestParam double orderAmount,
            @RequestBody(required = false) List<String> dishIds) {
        double discount = promotionService.calculateDiscount(
            promotionId, 
            orderAmount, 
            dishIds != null ? dishIds : List.of()
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("promotionId", promotionId);
        result.put("orderAmount", orderAmount);
        result.put("discountAmount", discount);
        result.put("finalAmount", orderAmount - discount);
        
        return ApiResponse.success(result);
    }
}
