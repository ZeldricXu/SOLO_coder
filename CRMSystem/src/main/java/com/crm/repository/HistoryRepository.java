package com.crm.repository;

import com.crm.entity.History;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoryRepository extends JpaRepository<History, Long> {
    List<History> findByCustomerId(String customerId);
    List<History> findByHistoryType(String historyType);
    List<History> findByRelatedId(String relatedId);
}
