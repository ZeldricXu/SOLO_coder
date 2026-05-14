package com.deviceops.repository;

import com.deviceops.entity.DeviceStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceStatisticsRepository extends JpaRepository<DeviceStatistics, String> {

    Optional<DeviceStatistics> findByStatMonth(String statMonth);
}
