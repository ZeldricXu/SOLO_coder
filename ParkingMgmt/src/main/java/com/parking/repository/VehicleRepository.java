package com.parking.repository;

import com.parking.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, String> {
    Optional<Vehicle> findByVehicleNumber(String vehicleNumber);
    Optional<Vehicle> findByVehicleId(String vehicleId);
}
