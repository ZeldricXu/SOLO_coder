package com.assetmanage.repository;

import com.assetmanage.entity.MaintenanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, String> {

    List<MaintenanceRecord> findByAssetId(String assetId);

    List<MaintenanceRecord> findByMaintenanceType(String maintenanceType);

    @Query("SELECT m FROM MaintenanceRecord m WHERE m.assetId = :assetId ORDER BY m.maintenanceDate DESC")
    List<MaintenanceRecord> findByAssetIdOrderByDateDesc(@Param("assetId") String assetId);

    @Query("SELECT m FROM MaintenanceRecord m WHERE m.nextMaintenance BETWEEN :start AND :end")
    List<MaintenanceRecord> findByNextMaintenanceBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    List<MaintenanceRecord> findByAssetIdOrderByMaintenanceDateDesc(String assetId);
}
