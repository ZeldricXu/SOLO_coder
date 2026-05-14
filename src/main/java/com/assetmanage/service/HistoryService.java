package com.assetmanage.service;

import com.assetmanage.common.IdGenerator;
import com.assetmanage.entity.AssetHistory;
import com.assetmanage.repository.AssetHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final AssetHistoryRepository historyRepository;

    @Transactional
    public void recordHistory(String assetId, String actionType, String actionDetails, String operatorId) {
        AssetHistory history = new AssetHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setAssetId(assetId);
        history.setActionType(actionType);
        history.setActionDetails(actionDetails);
        history.setOperatorId(operatorId);
        historyRepository.save(history);
        log.debug("记录资产历史: assetId={}, actionType={}", assetId, actionType);
    }

    public List<AssetHistory> getAssetHistory(String assetId) {
        return historyRepository.findByAssetIdOrderByCreatedAtDesc(assetId);
    }

    public List<AssetHistory> getHistoryByActionType(String actionType) {
        return historyRepository.findByActionType(actionType);
    }

    public List<AssetHistory> getHistoryByOperator(String operatorId) {
        return historyRepository.findByOperatorId(operatorId);
    }

    public List<AssetHistory> getAllHistory() {
        return historyRepository.findAll();
    }
}
