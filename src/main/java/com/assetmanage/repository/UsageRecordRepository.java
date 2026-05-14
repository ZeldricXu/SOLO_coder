package com.assetmanage.repository;

import com.assetmanage.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, String> {

    List<UsageRecord> findByAssetId(String assetId);

    List<UsageRecord> findByUserId(String userId);

    List<UsageRecord> findByUsageStatus(String usageStatus);

    @Query("SELECT u FROM UsageRecord u WHERE u.assetId = :assetId AND u.usageStatus = 'active'")
    Optional<UsageRecord> findActiveByAssetId(@Param("assetId") String assetId);

    List<UsageRecord> findByAssetIdOrderByUsageStartDesc(String assetId);
}
