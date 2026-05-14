package com.adplatform.repository;

import com.adplatform.entity.AdTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdTargetRepository extends JpaRepository<AdTarget, String> {
    Optional<AdTarget> findByTargetId(String targetId);
    List<AdTarget> findByAdId(String adId);
    Optional<AdTarget> findTopByAdIdOrderByCreatedAtDesc(String adId);
}
