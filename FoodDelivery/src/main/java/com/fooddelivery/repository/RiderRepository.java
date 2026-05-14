package com.fooddelivery.repository;

import com.fooddelivery.entity.Rider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RiderRepository extends JpaRepository<Rider, String> {
    Optional<Rider> findByRiderId(String riderId);
    List<Rider> findByRiderRegion(String region);
    List<Rider> findByRiderStatus(String status);
    List<Rider> findByRiderRegionAndRiderStatus(String region, String status);
    boolean existsByRiderId(String riderId);
}
