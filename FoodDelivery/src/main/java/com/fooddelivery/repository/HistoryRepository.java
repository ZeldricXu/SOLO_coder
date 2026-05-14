package com.fooddelivery.repository;

import com.fooddelivery.entity.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface HistoryRepository extends JpaRepository<History, String> {
    List<History> findByHistoryTypeOrderByCreatedAtDesc(String historyType);
    List<History> findByRelatedIdOrderByCreatedAtDesc(String relatedId);
    List<History> findByHistoryTypeAndRelatedIdOrderByCreatedAtDesc(String historyType, String relatedId);
}
