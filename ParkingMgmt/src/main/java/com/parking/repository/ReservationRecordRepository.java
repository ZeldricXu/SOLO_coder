package com.parking.repository;

import com.parking.entity.ReservationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRecordRepository extends JpaRepository<ReservationRecord, String> {
    Optional<ReservationRecord> findByReserveId(String reserveId);
    List<ReservationRecord> findBySpaceIdAndReserveStatus(String spaceId, String reserveStatus);
    List<ReservationRecord> findByVehicleId(String vehicleId);
    List<ReservationRecord> findByParkingId(String parkingId);
}
