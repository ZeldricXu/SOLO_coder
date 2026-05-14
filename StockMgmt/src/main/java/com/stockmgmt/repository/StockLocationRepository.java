package com.stockmgmt.repository;

import com.stockmgmt.entity.StockLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockLocationRepository extends JpaRepository<StockLocation, String>, JpaSpecificationExecutor<StockLocation> {

    Optional<StockLocation> findByLocationCode(String locationCode);

    List<StockLocation> findByWarehouseId(String warehouseId);

    List<StockLocation> findByWarehouseIdAndStatus(String warehouseId, String status);

    Optional<StockLocation> findByWarehouseIdAndLocationCode(String warehouseId, String locationCode);
}
