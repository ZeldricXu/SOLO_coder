package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.Category;
import com.cms.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @PostMapping
    public ApiResponse<Category> createCategory(@RequestBody Category category) {
        Category createdCategory = categoryService.createCategory(category);
        return ApiResponse.success(createdCategory);
    }

    @PutMapping("/{categoryId}")
    public ApiResponse<Category> updateCategory(@PathVariable String categoryId, @RequestBody Category category) {
        Category updatedCategory = categoryService.updateCategory(categoryId, category);
        return ApiResponse.success(updatedCategory);
    }

    @GetMapping("/{categoryId}")
    public ApiResponse<Category> getCategory(@PathVariable String categoryId) {
        Category category = categoryService.getCategoryById(categoryId);
        return ApiResponse.success(category);
    }

    @GetMapping
    public ApiResponse<List<Category>> getAllCategories() {
        List<Category> categories = categoryService.getAllCategories();
        return ApiResponse.success(categories);
    }

    @GetMapping("/active")
    public ApiResponse<List<Category>> getActiveCategories() {
        List<Category> categories = categoryService.getActiveCategories();
        return ApiResponse.success(categories);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Category>> getCategoriesByType(@PathVariable String type) {
        List<Category> categories = categoryService.getCategoriesByType(type);
        return ApiResponse.success(categories);
    }

    @GetMapping("/{categoryId}/children")
    public ApiResponse<List<Category>> getChildCategories(@PathVariable String categoryId) {
        List<Category> children = categoryService.getChildCategories(categoryId);
        return ApiResponse.success(children);
    }

    @DeleteMapping("/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable String categoryId) {
        categoryService.deleteCategory(categoryId);
        return ApiResponse.success(null);
    }
}
