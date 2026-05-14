package com.assetinventory.repository;

import com.assetinventory.entity.InventoryStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryStatisticsRepository extends JpaRepository<InventoryStatistics, String> {

    Optional<InventoryStatistics> findByStatMonth(String statMonth);
}
