package com.parking.repository;

import com.parking.entity.ExitRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExitRecordRepository extends JpaRepository<ExitRecord, String> {
    Optional<ExitRecord> findByExitId(String exitId);
    Optional<ExitRecord> findByEntryId(String entryId);
    List<ExitRecord> findByVehicleId(String vehicleId);
}
