package com.parking.repository;

import com.parking.entity.SettlementRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, String> {
    Optional<SettlementRecord> findBySettlementId(String settlementId);
    Optional<SettlementRecord> findByEntryId(String entryId);
    List<SettlementRecord> findByVehicleId(String vehicleId);
    List<SettlementRecord> findByPaymentStatus(String paymentStatus);
}
