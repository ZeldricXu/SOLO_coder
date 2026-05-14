package com.library.librarymgmt.controller;

import com.library.librarymgmt.config.LibraryConfig;
import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.service.CategoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final LibraryConfig libraryConfig;

    public CategoryController(CategoryService categoryService, LibraryConfig libraryConfig) {
        this.categoryService = categoryService;
        this.libraryConfig = libraryConfig;
    }

    @GetMapping
    public ApiResponse<List<String>> getAllCategories() {
        return ApiResponse.success(categoryService.getAllCategories());
    }

    @GetMapping("/{categoryName}/valid")
    public ApiResponse<Boolean> isCategoryValid(@PathVariable String categoryName) {
        return ApiResponse.success(categoryService.isCategoryValid(categoryName));
    }

    @GetMapping("/{categoryName}/config")
    public ApiResponse<LibraryConfig.Category.CategoryConfig> getCategoryConfig(@PathVariable String categoryName) {
        LibraryConfig.Category.CategoryConfig config = categoryService.getCategoryConfig(categoryName);
        if (config == null) {
            return ApiResponse.error(404, "分类配置不存在");
        }
        return ApiResponse.success(config);
    }

    @GetMapping("/{categoryName}/borrow-days")
    public ApiResponse<Integer> getMaxBorrowDays(@PathVariable String categoryName) {
        return ApiResponse.success(categoryService.getMaxBorrowDays(categoryName));
    }

    @GetMapping("/{categoryName}/reminder-policy")
    public ApiResponse<String> getReminderPolicy(@PathVariable String categoryName) {
        return ApiResponse.success(categoryService.getReminderPolicy(categoryName));
    }

    @PostMapping("/reload")
    public ApiResponse<String> reloadCategories() {
        categoryService.reloadCategories();
        return ApiResponse.success("分类配置已重新加载");
    }
}
