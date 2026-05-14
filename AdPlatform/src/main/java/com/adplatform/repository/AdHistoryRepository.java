package com.adplatform.repository;

import com.adplatform.entity.AdHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdHistoryRepository extends JpaRepository<AdHistory, String> {
    Optional<AdHistory> findByHistoryId(String historyId);
    List<AdHistory> findByAdId(String adId);
    List<AdHistory> findByAdIdAndHistoryType(String adId, String historyType);
    List<AdHistory> findByAdIdAndCreatedAtBetween(String adId, LocalDateTime startTime, LocalDateTime endTime);
    List<AdHistory> findByHistoryType(String historyType);
}
