package com.parking.repository;

import com.parking.entity.ParkingStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParkingStatisticsRepository extends JpaRepository<ParkingStatistics, String> {
    Optional<ParkingStatistics> findByStatId(String statId);
    Optional<ParkingStatistics> findByStatMonth(String statMonth);
}
