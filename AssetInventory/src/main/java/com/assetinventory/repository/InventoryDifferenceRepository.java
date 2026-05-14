package com.assetinventory.repository;

import com.assetinventory.entity.InventoryDifference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryDifferenceRepository extends JpaRepository<InventoryDifference, String> {

    List<InventoryDifference> findByDiffStatus(String diffStatus);

    List<InventoryDifference> findByAssetId(String assetId);

    List<InventoryDifference> findByCountId(String countId);

    Optional<InventoryDifference> findByDiffId(String diffId);
}
