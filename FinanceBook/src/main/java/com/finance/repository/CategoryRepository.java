package com.finance.repository;

import com.finance.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, String> {
    List<Category> findByCategoryType(String categoryType);
    List<Category> findByCategoryParent(String categoryParent);
    List<Category> findByCategoryStatus(String categoryStatus);
    Optional<Category> findByCategoryName(String categoryName);
}
