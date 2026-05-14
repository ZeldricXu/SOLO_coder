package com.parking.repository;

import com.parking.entity.PreCalculationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PreCalculationConfigRepository extends JpaRepository<PreCalculationConfig, Long> {

    @Query("SELECT p FROM PreCalculationConfig p WHERE p.enabled = true ORDER BY p.durationThresholdMinutes ASC")
    List<PreCalculationConfig> findAllEnabledOrderByThreshold();

    @Query("SELECT p FROM PreCalculationConfig p WHERE p.enabled = true AND p.durationThresholdMinutes <= :duration ORDER BY p.durationThresholdMinutes DESC")
    List<PreCalculationConfig> findByDurationThresholdLessThanEqualOrderByThresholdDesc(@Param("duration") Integer duration);

    Optional<PreCalculationConfig> findByDurationCategory(String durationCategory);

    boolean existsByDurationCategory(String durationCategory);
}
