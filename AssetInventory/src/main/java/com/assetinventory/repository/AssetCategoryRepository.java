package com.assetinventory.repository;

import com.assetinventory.entity.AssetCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssetCategoryRepository extends JpaRepository<AssetCategory, String> {

    List<AssetCategory> findByCategoryStatus(String categoryStatus);

    Optional<AssetCategory> findByCategoryCode(String categoryCode);

    Optional<AssetCategory> findByCategoryId(String categoryId);
}
