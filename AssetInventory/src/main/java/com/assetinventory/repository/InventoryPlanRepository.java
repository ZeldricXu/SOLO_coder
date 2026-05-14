package com.assetinventory.repository;

import com.assetinventory.entity.InventoryPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryPlanRepository extends JpaRepository<InventoryPlan, String> {

    List<InventoryPlan> findByPlanStatus(String planStatus);

    Optional<InventoryPlan> findByPlanId(String planId);
}
