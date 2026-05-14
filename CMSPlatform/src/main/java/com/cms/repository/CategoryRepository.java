package com.cms.repository;

import com.cms.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, String> {

    Optional<Category> findByCategoryName(String categoryName);

    List<Category> findByCategoryParent(String categoryParent);

    List<Category> findByCategoryStatus(String categoryStatus);

    List<Category> findByCategoryType(String categoryType);
}
