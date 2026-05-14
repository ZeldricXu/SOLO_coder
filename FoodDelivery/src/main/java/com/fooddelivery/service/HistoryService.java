package com.fooddelivery.service;

import com.fooddelivery.entity.History;
import com.fooddelivery.repository.HistoryRepository;
import com.fooddelivery.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRepository historyRepository;

    @Transactional
    public History recordHistory(String type, String relatedId, String action, String detail) {
        History history = new History();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setHistoryType(type);
        history.setRelatedId(relatedId);
        history.setAction(action);
        history.setDetail(detail);
        return historyRepository.save(history);
    }

    public List<History> getHistoryByType(String type) {
        return historyRepository.findByHistoryTypeOrderByCreatedAtDesc(type);
    }

    public List<History> getHistoryByRelatedId(String relatedId) {
        return historyRepository.findByRelatedIdOrderByCreatedAtDesc(relatedId);
    }

    public List<History> getHistoryByTypeAndRelatedId(String type, String relatedId) {
        return historyRepository.findByHistoryTypeAndRelatedIdOrderByCreatedAtDesc(type, relatedId);
    }

    public List<History> getAllHistory() {
        return historyRepository.findAll();
    }
}
