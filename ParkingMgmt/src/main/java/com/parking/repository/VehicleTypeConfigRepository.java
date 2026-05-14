package com.parking.repository;

import com.parking.entity.VehicleTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleTypeConfigRepository extends JpaRepository<VehicleTypeConfig, Long> {

    Optional<VehicleTypeConfig> findByVehicleType(String vehicleType);

    @Query("SELECT v FROM VehicleTypeConfig v WHERE v.vehicleType = :vehicleType AND v.enabled = true")
    Optional<VehicleTypeConfig> findEnabledByVehicleType(@Param("vehicleType") String vehicleType);

    List<VehicleTypeConfig> findByEnabledTrue();

    boolean existsByVehicleType(String vehicleType);
}
