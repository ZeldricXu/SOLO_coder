package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.entity.AssetCategory;
import com.assetinventory.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    @Autowired
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AssetCategory>> createCategory(@RequestBody AssetCategory category) {
        AssetCategory created = categoryService.createCategory(
                category.getCategoryCode(),
                category.getCategoryName(),
                category.getCategoryDescription()
        );
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AssetCategory>>> getAllCategories() {
        List<AssetCategory> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<AssetCategory>>> getActiveCategories() {
        List<AssetCategory> categories = categoryService.getActiveCategories();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @GetMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<AssetCategory>> getCategoryById(@PathVariable String categoryId) {
        return categoryService.getCategoryById(categoryId)
                .map(category -> ResponseEntity.ok(ApiResponse.success(category)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "资产类别不存在")));
    }
}
