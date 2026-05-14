package com.finance.service;

import com.finance.entity.Category;
import com.finance.entity.Record;
import com.finance.exception.FinanceException;
import com.finance.repository.CategoryRepository;
import com.finance.repository.RecordRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final RecordRepository recordRepository;

    @Transactional
    public Category createCategory(String categoryName, String categoryType, String categoryParent) {
        if (categoryRepository.findByCategoryName(categoryName).isPresent()) {
            throw new FinanceException(400, "分类已存在: " + categoryName);
        }

        Category category = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName(categoryName)
                .categoryType(categoryType)
                .categoryParent(categoryParent)
                .categoryStatus("active")
                .createdAt(LocalDateTime.now())
                .build();

        Category saved = categoryRepository.save(category);
        log.info("创建分类成功: categoryName={}", categoryName);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Category> getCategoriesByType(String categoryType) {
        return categoryRepository.findByCategoryType(categoryType);
    }

    @Transactional(readOnly = true)
    public List<Category> getActiveCategories() {
        return categoryRepository.findByCategoryStatus("active");
    }

    @Transactional(readOnly = true)
    public Category getCategoryByName(String categoryName) {
        return categoryRepository.findByCategoryName(categoryName)
                .orElseThrow(() -> FinanceException.categoryNotFound(categoryName));
    }

    @Transactional(readOnly = true)
    public boolean existsByName(String categoryName) {
        return categoryRepository.findByCategoryName(categoryName).isPresent();
    }

    @Transactional
    public Category matchCategory(String recordType, String categoryName) {
        Optional<Category> categoryOpt = categoryRepository.findByCategoryName(categoryName);

        if (categoryOpt.isPresent()) {
            Category category = categoryOpt.get();
            if (category.getCategoryType().equals(recordType)) {
                log.debug("分类匹配成功: category={}, type={}", categoryName, recordType);
                return category;
            }
        }

        log.warn("分类匹配失败: category={}, type={}", categoryName, recordType);
        return null;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCategoryStatistics(String accountId, LocalDateTime startTime, LocalDateTime endTime) {
        List<Object[]> categoryStats = recordRepository.sumByCategoryAndTimeRange(accountId, startTime, endTime);

        Map<String, BigDecimal> categoryAmounts = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Object[] stat : categoryStats) {
            String category = (String) stat[0];
            BigDecimal amount = (BigDecimal) stat[1];
            categoryAmounts.put(category, amount);
            totalAmount = totalAmount.add(amount);
        }

        List<Map<String, Object>> categoryList = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : categoryAmounts.entrySet()) {
            BigDecimal percentage = totalAmount.compareTo(BigDecimal.ZERO) > 0
                    ? entry.getValue().multiply(new BigDecimal("100")).divide(totalAmount, 2, BigDecimal.ROUND_HALF_UP)
                    : BigDecimal.ZERO;

            Map<String, Object> categoryInfo = new HashMap<>();
            categoryInfo.put("category_name", entry.getKey());
            categoryInfo.put("amount", entry.getValue());
            categoryInfo.put("percentage", percentage);
            categoryList.add(categoryInfo);
        }

        categoryList.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));

        Map<String, Object> result = new HashMap<>();
        result.put("total_amount", totalAmount);
        result.put("category_list", categoryList);
        result.put("category_count", categoryList.size());

        return result;
    }

    @Transactional
    public Category updateCategory(String categoryId, String categoryName, String status) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> FinanceException.categoryNotFound(categoryId));

        if (categoryName != null) category.setCategoryName(categoryName);
        if (status != null) category.setCategoryStatus(status);

        return categoryRepository.save(category);
    }
}
