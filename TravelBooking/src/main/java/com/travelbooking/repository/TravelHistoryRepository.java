package com.travelbooking.repository;

import com.travelbooking.model.TravelHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TravelHistoryRepository extends JpaRepository<TravelHistory, Long> {
    List<TravelHistory> findByReferenceIdOrderByCreatedAtDesc(String referenceId);
    List<TravelHistory> findByRecordTypeOrderByCreatedAtDesc(String recordType);
}
