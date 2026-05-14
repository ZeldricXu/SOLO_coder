package com.adplatform.repository;

import com.adplatform.entity.AdPlacement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdPlacementRepository extends JpaRepository<AdPlacement, String> {
    Optional<AdPlacement> findByPlacementId(String placementId);
    List<AdPlacement> findByAdId(String adId);
    List<AdPlacement> findByPlacementStatus(String placementStatus);
    List<AdPlacement> findByAdIdAndPlacementStatus(String adId, String placementStatus);
}
