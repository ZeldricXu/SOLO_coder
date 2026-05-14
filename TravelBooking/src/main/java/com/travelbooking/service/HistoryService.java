package com.travelbooking.service;

import com.travelbooking.model.TravelHistory;
import com.travelbooking.repository.TravelHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final TravelHistoryRepository travelHistoryRepository;

    @Transactional
    public TravelHistory recordHistory(String recordType, String referenceId, String action, String description) {
        TravelHistory history = new TravelHistory();
        history.setRecordType(recordType);
        history.setReferenceId(referenceId);
        history.setAction(action);
        history.setDescription(description);
        history.setCreatedAt(Instant.now());
        return travelHistoryRepository.save(history);
    }

    public List<TravelHistory> getAllHistory() {
        return travelHistoryRepository.findAll();
    }

    public List<TravelHistory> getHistoryByReferenceId(String referenceId) {
        return travelHistoryRepository.findByReferenceIdOrderByCreatedAtDesc(referenceId);
    }

    public List<TravelHistory> getHistoryByRecordType(String recordType) {
        return travelHistoryRepository.findByRecordTypeOrderByCreatedAtDesc(recordType);
    }
}
