package com.assetmanage.repository;

import com.assetmanage.entity.InventoryDifference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryDifferenceRepository extends JpaRepository<InventoryDifference, String> {

    List<InventoryDifference> findByCheckId(String checkId);

    List<InventoryDifference> findByAssetId(String assetId);

    List<InventoryDifference> findByDiffStatus(String diffStatus);

    List<InventoryDifference> findByCheckIdAndDiffStatus(String checkId, String diffStatus);
}
