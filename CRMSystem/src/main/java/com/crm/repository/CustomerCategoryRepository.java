package com.crm.repository;

import com.crm.entity.CustomerCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerCategoryRepository extends JpaRepository<CustomerCategory, Long> {
    List<CustomerCategory> findByCustomerId(String customerId);
    List<CustomerCategory> findByCategoryId(String categoryId);
    void deleteByCustomerIdAndCategoryId(String customerId, String categoryId);
}
