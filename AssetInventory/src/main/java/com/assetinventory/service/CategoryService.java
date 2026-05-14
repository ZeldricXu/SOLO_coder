package com.assetinventory.service;

import com.assetinventory.config.CategoryConfig;
import com.assetinventory.config.CategoryConfig.CategoryDefault;
import com.assetinventory.entity.AssetCategory;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.AssetCategoryRepository;
import com.assetinventory.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Transactional
public class CategoryService {

    private static final Logger logger = LoggerFactory.getLogger(CategoryService.class);

    private final AssetCategoryRepository categoryRepository;
    private final CategoryConfig categoryConfig;
    private final Map<String, AssetCategory> categoryCache = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    @Autowired
    public CategoryService(AssetCategoryRepository categoryRepository,
                          CategoryConfig categoryConfig) {
        this.categoryRepository = categoryRepository;
        this.categoryConfig = categoryConfig;
    }

    @PostConstruct
    public void init() {
        if (!initialized && categoryConfig.isEnabled() && categoryConfig.isAutoLoad()) {
            loadDefaultCategories();
            initialized = true;
        }
    }

    public void loadDefaultCategories() {
        logger.info("Loading default categories from configuration...");

        List<CategoryDefault> defaults = categoryConfig.getDefaults();
        if (defaults == null || defaults.isEmpty()) {
            logger.warn("No default categories configured");
            return;
        }

        for (CategoryDefault defaultCategory : defaults) {
            String code = defaultCategory.getCode();
            if (code == null || code.isEmpty()) {
                logger.warn("Skipping default category with null/empty code");
                continue;
            }

            try {
                if (!categoryExists(code)) {
                    createFromDefault(defaultCategory);
                    logger.info("Created category from config: {}", code);
                } else {
                    logger.debug("Category already exists: {}", code);
                }
            } catch (Exception e) {
                logger.error("Failed to load default category: {}", code, e);
            }
        }

        refreshCache();
        logger.info("Default categories loading complete. Total: {}", defaults.size());
    }

    private void createFromDefault(CategoryDefault defaultCategory) {
        AssetCategory category = new AssetCategory();
        category.setCategoryId(IdGenerator.generateCategoryId());
        category.setCategoryCode(defaultCategory.getCode());
        category.setCategoryName(defaultCategory.getName());
        category.setCategoryDescription(defaultCategory.getDescription());
        category.setCategoryStatus(
                defaultCategory.getStatus() != null ? defaultCategory.getStatus() : "active"
        );
        category.setCreatedAt(IdGenerator.now());

        categoryRepository.save(category);
    }

    public AssetCategory createCategory(String categoryCode, String categoryName, String categoryDescription) {
        Optional<AssetCategory> existing = categoryRepository.findByCategoryCode(categoryCode);
        if (existing.isPresent()) {
            throw new InventoryException(400, "资产类别代码已存在");
        }

        AssetCategory category = new AssetCategory();
        category.setCategoryId(IdGenerator.generateCategoryId());
        category.setCategoryCode(categoryCode);
        category.setCategoryName(categoryName);
        category.setCategoryDescription(categoryDescription);
        category.setCategoryStatus("active");
        category.setCreatedAt(IdGenerator.now());

        AssetCategory saved = categoryRepository.save(category);
        categoryCache.put(saved.getCategoryCode(), saved);

        logger.info("Created category: {} - {}", categoryCode, categoryName);

        return saved;
    }

    public List<AssetCategory> getAllCategories() {
        if (categoryCache.isEmpty()) {
            refreshCache();
        }
        return new ArrayList<>(categoryCache.values());
    }

    public List<AssetCategory> getActiveCategories() {
        if (categoryCache.isEmpty()) {
            refreshCache();
        }
        return categoryCache.values().stream()
                .filter(cat -> "active".equals(cat.getCategoryStatus()))
                .collect(Collectors.toList());
    }

    public Optional<AssetCategory> getCategoryById(String categoryId) {
        return categoryRepository.findByCategoryId(categoryId);
    }

    public Optional<AssetCategory> getCategoryByCode(String categoryCode) {
        AssetCategory cached = categoryCache.get(categoryCode);
        if (cached != null) {
            return Optional.of(cached);
        }
        return categoryRepository.findByCategoryCode(categoryCode);
    }

    public AssetCategory updateCategoryStatus(String categoryId, String status) {
        AssetCategory category = categoryRepository.findByCategoryId(categoryId)
                .orElseThrow(() -> new InventoryException(404, "资产类别不存在"));
        category.setCategoryStatus(status);

        AssetCategory updated = categoryRepository.save(category);
        categoryCache.put(updated.getCategoryCode(), updated);

        logger.info("Updated category status: {} -> {}", updated.getCategoryCode(), status);

        return updated;
    }

    public AssetCategory updateCategory(String categoryId, String categoryName, String categoryDescription) {
        AssetCategory category = categoryRepository.findByCategoryId(categoryId)
                .orElseThrow(() -> new InventoryException(404, "资产类别不存在"));

        if (categoryName != null && !categoryName.isEmpty()) {
            category.setCategoryName(categoryName);
        }
        if (categoryDescription != null) {
            category.setCategoryDescription(categoryDescription);
        }

        AssetCategory updated = categoryRepository.save(category);
        categoryCache.put(updated.getCategoryCode(), updated);

        logger.info("Updated category: {}", updated.getCategoryCode());

        return updated;
    }

    public void deleteCategory(String categoryId) {
        AssetCategory category = categoryRepository.findByCategoryId(categoryId)
                .orElseThrow(() -> new InventoryException(404, "资产类别不存在"));

        categoryRepository.delete(category);
        categoryCache.remove(category.getCategoryCode());

        logger.info("Deleted category: {}", category.getCategoryCode());
    }

    public void refreshCache() {
        List<AssetCategory> all = categoryRepository.findAll();
        categoryCache.clear();
        for (AssetCategory category : all) {
            categoryCache.put(category.getCategoryCode(), category);
        }
        logger.debug("Category cache refreshed. Total: {}", all.size());
    }

    public boolean categoryExists(String categoryCode) {
        if (categoryCache.containsKey(categoryCode)) {
            return true;
        }
        return categoryRepository.findByCategoryCode(categoryCode).isPresent();
    }

    public AssetCategory getOrCreateCategory(String categoryCode, String defaultName) {
        Optional<AssetCategory> existing = getCategoryByCode(categoryCode);
        if (existing.isPresent()) {
            return existing.get();
        }

        logger.warn("Category not found, creating: {} with default name: {}", categoryCode, defaultName);
        return createCategory(categoryCode, defaultName, "自动创建的类别");
    }

    public int getCategoryCount() {
        return getAllCategories().size();
    }

    public int getActiveCategoryCount() {
        return getActiveCategories().size();
    }

    public boolean isEnabled() {
        return categoryConfig.isEnabled();
    }

    public boolean isAutoLoad() {
        return categoryConfig.isAutoLoad();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void reloadDefaults() {
        logger.info("Reloading default categories from configuration...");
        loadDefaultCategories();
    }
}
