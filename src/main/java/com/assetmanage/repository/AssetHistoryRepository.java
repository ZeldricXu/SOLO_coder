package com.assetmanage.repository;

import com.assetmanage.entity.AssetHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetHistoryRepository extends JpaRepository<AssetHistory, String> {

    List<AssetHistory> findByAssetIdOrderByCreatedAtDesc(String assetId);

    List<AssetHistory> findByActionType(String actionType);

    List<AssetHistory> findByOperatorId(String operatorId);

    @Query("SELECT h FROM AssetHistory h WHERE h.assetId = :assetId ORDER BY h.createdAt DESC")
    List<AssetHistory> findHistoryByAssetId(@Param("assetId") String assetId);
}
