package com.assetmanage.repository;

import com.assetmanage.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findByAssetStatus(String assetStatus);

    List<Asset> findByAssetType(String assetType);

    List<Asset> findByAssetCategory(String assetCategory);

    List<Asset> findByDepartment(String department);

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.assetStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT SUM(a.currentValue) FROM Asset a")
    java.math.BigDecimal sumCurrentValue();

    @Query("SELECT a FROM Asset a WHERE a.assetStatus NOT IN ('scrapped')")
    List<Asset> findActiveAssets();
}
