package com.parking.repository;

import com.parking.entity.EntryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EntryRecordRepository extends JpaRepository<EntryRecord, String> {
    Optional<EntryRecord> findByEntryId(String entryId);
    
    List<EntryRecord> findByVehicleIdAndEntryStatus(String vehicleId, String entryStatus);
    
    List<EntryRecord> findByEntryStatus(String entryStatus);
    
    List<EntryRecord> findByEntryStatusAndVehicleType(String entryStatus, String vehicleType);
    
    long countByEntryStatus(String entryStatus);
    
    long countByEntryStatusAndVehicleType(String entryStatus, String vehicleType);
    
    @Query("SELECT e FROM EntryRecord e WHERE e.parkingId = :parkingId AND e.entryStatus = :status")
    List<EntryRecord> findByParkingIdAndEntryStatus(@Param("parkingId") String parkingId, @Param("status") String status);
    
    @Query("SELECT COUNT(e) FROM EntryRecord e WHERE e.parkingId = :parkingId AND e.entryStatus = :status")
    long countByParkingIdAndEntryStatus(@Param("parkingId") String parkingId, @Param("status") String status);
}
