package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.dto.CategoryRequest;
import com.crm.dto.CustomerCategoryRequest;
import com.crm.entity.Category;
import com.crm.entity.CustomerCategory;
import com.crm.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @PostMapping
    public ApiResponse<Category> createCategory(@Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.createCategory(request);
        return ApiResponse.success(category);
    }

    @GetMapping("/{categoryId}")
    public ApiResponse<Category> getCategoryById(@PathVariable String categoryId) {
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

    @PostMapping("/assign")
    public ApiResponse<Void> assignCategoryToCustomer(@Valid @RequestBody CustomerCategoryRequest request) {
        categoryService.assignCategoryToCustomer(request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/remove")
    public ApiResponse<Void> removeCategoryFromCustomer(@Valid @RequestBody CustomerCategoryRequest request) {
        categoryService.removeCategoryFromCustomer(request);
        return ApiResponse.success(null);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<CustomerCategory>> getCustomerCategories(@PathVariable String customerId) {
        List<CustomerCategory> categories = categoryService.getCustomerCategories(customerId);
        return ApiResponse.success(categories);
    }
}
