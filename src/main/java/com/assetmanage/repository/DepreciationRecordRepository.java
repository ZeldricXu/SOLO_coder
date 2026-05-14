package com.assetmanage.repository;

import com.assetmanage.entity.DepreciationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepreciationRecordRepository extends JpaRepository<DepreciationRecord, String> {

    List<DepreciationRecord> findByAssetId(String assetId);

    List<DepreciationRecord> findByDepreciationPeriod(String depreciationPeriod);

    @Query("SELECT d FROM DepreciationRecord d WHERE d.assetId = :assetId ORDER BY d.calculatedAt DESC")
    List<DepreciationRecord> findByAssetIdOrderByCalculatedAtDesc(@Param("assetId") String assetId);

    @Query("SELECT d FROM DepreciationRecord d WHERE d.assetId = :assetId AND d.depreciationPeriod = :period")
    Optional<DepreciationRecord> findByAssetIdAndPeriod(@Param("assetId") String assetId, @Param("period") String period);

    @Query("SELECT d FROM DepreciationRecord d WHERE d.assetId = :assetId AND d.depreciationPeriod BETWEEN :startPeriod AND :endPeriod ORDER BY d.depreciationPeriod")
    List<DepreciationRecord> findByAssetIdAndPeriodBetween(@Param("assetId") String assetId, @Param("startPeriod") String startPeriod, @Param("endPeriod") String endPeriod);
}
