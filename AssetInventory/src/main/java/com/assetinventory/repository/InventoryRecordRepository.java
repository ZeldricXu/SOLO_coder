package com.assetinventory.repository;

import com.assetinventory.entity.InventoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryRecordRepository extends JpaRepository<InventoryRecord, String> {

    List<InventoryRecord> findByTaskId(String taskId);

    List<InventoryRecord> findByAssetId(String assetId);

    List<InventoryRecord> findByCountStatus(String countStatus);

    Optional<InventoryRecord> findByCountId(String countId);
}
