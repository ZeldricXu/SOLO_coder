package com.adplatform.repository;

import com.adplatform.entity.AdConsume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdConsumeRepository extends JpaRepository<AdConsume, String> {
    Optional<AdConsume> findByConsumeId(String consumeId);
    List<AdConsume> findByAdId(String adId);
    List<AdConsume> findByAdIdAndConsumeTimeBetween(String adId, LocalDateTime startTime, LocalDateTime endTime);
    List<AdConsume> findByAdIdAndConsumeType(String adId, String consumeType);
}
