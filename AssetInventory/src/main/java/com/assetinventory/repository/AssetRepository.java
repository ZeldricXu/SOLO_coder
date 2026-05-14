package com.assetinventory.repository;

import com.assetinventory.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findByAssetStatus(String assetStatus);

    List<Asset> findByAssetCategory(String assetCategory);

    Optional<Asset> findByAssetId(String assetId);
}
