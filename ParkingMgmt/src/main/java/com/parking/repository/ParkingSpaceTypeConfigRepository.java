package com.parking.repository;

import com.parking.entity.ParkingSpaceTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParkingSpaceTypeConfigRepository extends JpaRepository<ParkingSpaceTypeConfig, Long> {

    Optional<ParkingSpaceTypeConfig> findBySpaceType(String spaceType);

    @Query("SELECT p FROM ParkingSpaceTypeConfig p WHERE p.spaceType = :spaceType AND p.enabled = true")
    Optional<ParkingSpaceTypeConfig> findEnabledBySpaceType(@Param("spaceType") String spaceType);

    List<ParkingSpaceTypeConfig> findByEnabledTrue();

    boolean existsBySpaceType(String spaceType);
}
