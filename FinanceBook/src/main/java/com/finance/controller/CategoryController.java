package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.Category;
import com.finance.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<List<Category>> getAllCategories() {
        List<Category> categories = categoryService.getAllCategories();
        return ApiResponse.success(categories);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Category>> getCategoriesByType(@PathVariable String type) {
        List<Category> categories = categoryService.getCategoriesByType(type);
        return ApiResponse.success(categories);
    }

    @GetMapping("/statistics/{accountId}")
    public ApiResponse<Map<String, Object>> getCategoryStatistics(
            @PathVariable String accountId,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {

        LocalDateTime startTime;
        LocalDateTime endTime;

        if (start_date != null && end_date != null) {
            startTime = LocalDate.parse(start_date).atStartOfDay();
            endTime = LocalDate.parse(end_date).atTime(LocalTime.MAX);
        } else {
            LocalDate now = LocalDate.now();
            startTime = now.withDayOfMonth(1).atStartOfDay();
            endTime = now.atTime(LocalTime.MAX);
        }

        Map<String, Object> statistics = categoryService.getCategoryStatistics(accountId, startTime, endTime);
        return ApiResponse.success(statistics);
    }

    @PostMapping("/create")
    public ApiResponse<Category> createCategory(@RequestBody Map<String, String> request) {
        Category category = categoryService.createCategory(
                request.get("category_name"),
                request.get("category_type"),
                request.get("category_parent")
        );
        return ApiResponse.success(category);
    }
}
