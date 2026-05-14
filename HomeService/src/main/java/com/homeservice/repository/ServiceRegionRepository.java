package com.homeservice.repository;

import com.homeservice.entity.ServiceRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceRegionRepository extends JpaRepository<ServiceRegion, Long> {
    Optional<ServiceRegion> findByRegionCode(String regionCode);
    List<ServiceRegion> findByIsActiveTrue();
    boolean existsByRegionCode(String regionCode);
}
