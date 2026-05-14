package com.parking.repository;

import com.parking.entity.ParkingLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParkingLotRepository extends JpaRepository<ParkingLot, String> {
    Optional<ParkingLot> findByParkingId(String parkingId);
}
