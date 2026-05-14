package com.crm.service;

import com.crm.common.IdGenerator;
import com.crm.dto.CategoryRequest;
import com.crm.dto.CustomerCategoryRequest;
import com.crm.entity.Category;
import com.crm.entity.CustomerCategory;
import com.crm.exception.BusinessException;
import com.crm.repository.CategoryRepository;
import com.crm.repository.CustomerCategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CategoryService {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CustomerCategoryRepository customerCategoryRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Category createCategory(CategoryRequest request) {
        Category category = new Category();
        category.setCategoryId(IdGenerator.generateCategoryId());
        category.setCategoryName(request.getCategoryName());
        category.setCategoryType(request.getCategoryType() != null ? request.getCategoryType() : "value");
        category.setCategoryLevel(request.getCategoryLevel() != null ? request.getCategoryLevel() : 1);
        category.setCategoryStatus(request.getCategoryStatus() != null ? request.getCategoryStatus() : "active");
        return categoryRepository.save(category);
    }

    public Category getCategoryById(String categoryId) {
        return categoryRepository.findByCategoryId(categoryId)
                .orElseThrow(() -> new BusinessException("分类不存在"));
    }

    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    public List<Category> getActiveCategories() {
        return categoryRepository.findByCategoryStatus("active");
    }

    @Transactional
    public void assignCategoryToCustomer(CustomerCategoryRequest request) {
        customerService.getCustomerById(request.getCustomerId());
        getCategoryById(request.getCategoryId());

        CustomerCategory customerCategory = new CustomerCategory();
        customerCategory.setCustomerId(request.getCustomerId());
        customerCategory.setCategoryId(request.getCategoryId());
        customerCategoryRepository.save(customerCategory);

        historyService.recordHistory(
                request.getCustomerId(),
                "category",
                request.getCategoryId(),
                "assign",
                "分配分类：" + request.getCategoryId(),
                null
        );
    }

    @Transactional
    public void removeCategoryFromCustomer(CustomerCategoryRequest request) {
        customerCategoryRepository.deleteByCustomerIdAndCategoryId(
                request.getCustomerId(),
                request.getCategoryId()
        );

        historyService.recordHistory(
                request.getCustomerId(),
                "category",
                request.getCategoryId(),
                "remove",
                "移除分类：" + request.getCategoryId(),
                null
        );
    }

    public List<CustomerCategory> getCustomerCategories(String customerId) {
        return customerCategoryRepository.findByCustomerId(customerId);
    }
}
