package com.fitnesscenter.repository;

import com.fitnesscenter.model.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface HistoryRepository extends JpaRepository<History, String> {
    
    Optional<History> findByHistoryId(String historyId);
    
    List<History> findByMemberId(String memberId);
    
    List<History> findByActionType(String actionType);
    
    List<History> findByMemberIdAndActionType(String memberId, String actionType);
    
    List<History> findByRelatedId(String relatedId);
    
    List<History> findByActionTimeBetween(Instant startTime, Instant endTime);
    
    List<History> findByMemberIdAndActionTimeBetween(String memberId, Instant startTime, Instant endTime);
    
    boolean existsByHistoryId(String historyId);
}
