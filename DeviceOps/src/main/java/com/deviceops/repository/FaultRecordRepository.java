package com.deviceops.repository;

import com.deviceops.entity.FaultRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FaultRecordRepository extends JpaRepository<FaultRecord, String> {

    List<FaultRecord> findByDeviceIdOrderByReportedAtDesc(String deviceId);

    List<FaultRecord> findByFaultStatus(String faultStatus);

    List<FaultRecord> findByReportedAtBetween(LocalDateTime start, LocalDateTime end);

    long countByFaultStatus(String faultStatus);

    long countByReportedAtBetween(LocalDateTime start, LocalDateTime end);
}
