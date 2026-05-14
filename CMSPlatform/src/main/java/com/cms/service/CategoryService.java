package com.cms.service;

import com.cms.entity.Category;
import com.cms.exception.BusinessException;
import com.cms.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CategoryService {

    @Autowired
    private CategoryRepository categoryRepository;

    @Transactional
    public Category createCategory(Category category) {
        if (categoryRepository.findByCategoryName(category.getCategoryName()).isPresent()) {
            throw new BusinessException(400, "分类名称已存在");
        }

        Category newCategory = new Category();
        newCategory.setCategoryId("category_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        newCategory.setCategoryName(category.getCategoryName());
        newCategory.setCategoryType(category.getCategoryType() != null ? category.getCategoryType() : "default");
        newCategory.setCategoryParent(category.getCategoryParent());
        newCategory.setCategoryDescription(category.getCategoryDescription());
        newCategory.setCategoryStatus(category.getCategoryStatus() != null ? category.getCategoryStatus() : "active");
        newCategory.setSortOrder(category.getSortOrder() != null ? category.getSortOrder() : 0);
        newCategory.setContentCount(0L);

        return categoryRepository.save(newCategory);
    }

    @Transactional
    public Category updateCategory(String categoryId, Category category) {
        Category existingCategory = getCategoryById(categoryId);

        if (category.getCategoryName() != null) {
            Optional<Category> duplicate = categoryRepository.findByCategoryName(category.getCategoryName());
            if (duplicate.isPresent() && !duplicate.get().getCategoryId().equals(categoryId)) {
                throw new BusinessException(400, "分类名称已存在");
            }
            existingCategory.setCategoryName(category.getCategoryName());
        }
        if (category.getCategoryType() != null) {
            existingCategory.setCategoryType(category.getCategoryType());
        }
        if (category.getCategoryParent() != null) {
            existingCategory.setCategoryParent(category.getCategoryParent());
        }
        if (category.getCategoryDescription() != null) {
            existingCategory.setCategoryDescription(category.getCategoryDescription());
        }
        if (category.getCategoryStatus() != null) {
            existingCategory.setCategoryStatus(category.getCategoryStatus());
        }
        if (category.getSortOrder() != null) {
            existingCategory.setSortOrder(category.getSortOrder());
        }

        return categoryRepository.save(existingCategory);
    }

    public Category getCategoryById(String categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(404, "分类不存在"));
    }

    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    public List<Category> getActiveCategories() {
        return categoryRepository.findByCategoryStatus("active");
    }

    public List<Category> getCategoriesByType(String type) {
        return categoryRepository.findByCategoryType(type);
    }

    public List<Category> getChildCategories(String parentId) {
        return categoryRepository.findByCategoryParent(parentId);
    }

    @Transactional
    public void deleteCategory(String categoryId) {
        Category category = getCategoryById(categoryId);
        if (category.getContentCount() > 0) {
            throw new BusinessException(400, "该分类下存在内容，无法删除");
        }
        categoryRepository.delete(category);
    }

    @Transactional
    public void incrementContentCount(String categoryId) {
        Category category = getCategoryById(categoryId);
        category.setContentCount(category.getContentCount() + 1);
        categoryRepository.save(category);
    }

    @Transactional
    public void decrementContentCount(String categoryId) {
        Category category = getCategoryById(categoryId);
        category.setContentCount(Math.max(0, category.getContentCount() - 1));
        categoryRepository.save(category);
    }
}
