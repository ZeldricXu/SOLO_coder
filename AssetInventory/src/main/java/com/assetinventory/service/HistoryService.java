package com.assetinventory.service;

import com.assetinventory.entity.InventoryHistory;
import com.assetinventory.repository.InventoryHistoryRepository;
import com.assetinventory.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class HistoryService {

    private final InventoryHistoryRepository historyRepository;

    @Autowired
    public HistoryService(InventoryHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public void recordTaskHistory(String taskId, String action, String description) {
        InventoryHistory history = new InventoryHistory();
        history.setHistoryType("task");
        history.setReferenceId(taskId);
        history.setAction(action);
        history.setDescription(description);
        history.setCreatedAt(IdGenerator.now());
        historyRepository.save(history);
    }

    public void recordCountingHistory(String countId, String action, String description) {
        InventoryHistory history = new InventoryHistory();
        history.setHistoryType("counting");
        history.setReferenceId(countId);
        history.setAction(action);
        history.setDescription(description);
        history.setCreatedAt(IdGenerator.now());
        historyRepository.save(history);
    }

    public void recordDifferenceHistory(String diffId, String action, String description) {
        InventoryHistory history = new InventoryHistory();
        history.setHistoryType("difference");
        history.setReferenceId(diffId);
        history.setAction(action);
        history.setDescription(description);
        history.setCreatedAt(IdGenerator.now());
        historyRepository.save(history);
    }

    public List<InventoryHistory> getAllHistory() {
        return historyRepository.findAll();
    }

    public List<InventoryHistory> getHistoryByType(String historyType) {
        return historyRepository.findByHistoryTypeOrderByCreatedAtDesc(historyType);
    }

    public List<InventoryHistory> getHistoryByReferenceId(String referenceId) {
        return historyRepository.findByReferenceId(referenceId);
    }
}
