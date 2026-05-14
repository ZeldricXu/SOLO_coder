package com.library.librarymgmt.service;

import com.library.librarymgmt.config.LibraryConfig;
import com.library.librarymgmt.exception.LibraryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {

    private static final Logger logger = LoggerFactory.getLogger(CategoryService.class);

    private final LibraryConfig libraryConfig;

    public CategoryService(LibraryConfig libraryConfig) {
        this.libraryConfig = libraryConfig;
    }

    public List<String> getAllCategories() {
        return libraryConfig.getCategory().getAvailable();
    }

    public boolean isCategoryValid(String categoryName) {
        if (categoryName == null || categoryName.trim().isEmpty()) {
            return false;
        }
        return libraryConfig.getCategory().isCategoryValid(categoryName.trim());
    }

    public void validateCategory(String categoryName) {
        if (!isCategoryValid(categoryName)) {
            throw new LibraryException(400, "无效的图书分类: " + categoryName);
        }
        LibraryConfig.Category.CategoryConfig config = libraryConfig.getCategory().getCategoryConfig(categoryName);
        if (config != null && !config.isEnabled()) {
            throw new LibraryException(400, "图书分类已禁用: " + categoryName);
        }
    }

    public LibraryConfig.Category.CategoryConfig getCategoryConfig(String categoryName) {
        return libraryConfig.getCategory().getCategoryConfig(categoryName);
    }

    public int getMaxBorrowDays(String categoryName) {
        LibraryConfig.Category.CategoryConfig config = getCategoryConfig(categoryName);
        if (config != null) {
            return config.getMaxBorrowDays();
        }
        return libraryConfig.getBorrow().getDefaultDays();
    }

    public String getReminderPolicy(String categoryName) {
        LibraryConfig.Category.CategoryConfig config = getCategoryConfig(categoryName);
        if (config != null) {
            return config.getReminderPolicy();
        }
        return "default";
    }

    public void reloadCategories() {
        logger.info("重新加载图书分类配置");
    }
}
