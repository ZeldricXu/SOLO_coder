package com.adplatform.service;

import com.adplatform.entity.AdHistory;
import com.adplatform.exception.BusinessException;
import com.adplatform.repository.AdHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class HistoryService {
    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);
    
    private final AdHistoryRepository adHistoryRepository;

    public HistoryService(AdHistoryRepository adHistoryRepository) {
        this.adHistoryRepository = adHistoryRepository;
    }

    public List<AdHistory> getHistoryByAdId(String adId) {
        List<AdHistory> histories = adHistoryRepository.findByAdId(adId);
        logger.debug("查询广告历史: adId={}, 记录数={}", adId, histories.size());
        return histories;
    }

    public List<AdHistory> getHistoryByAdIdAndType(String adId, String historyType) {
        return adHistoryRepository.findByAdIdAndHistoryType(adId, historyType);
    }

    public List<AdHistory> getHistoryByAdIdAndTimeRange(String adId, LocalDateTime startTime, LocalDateTime endTime) {
        return adHistoryRepository.findByAdIdAndCreatedAtBetween(adId, startTime, endTime);
    }

    public List<AdHistory> getHistoryByType(String historyType) {
        return adHistoryRepository.findByHistoryType(historyType);
    }

    public Optional<AdHistory> getHistoryById(String historyId) {
        return adHistoryRepository.findByHistoryId(historyId);
    }

    public List<AdHistory> getStatusChangeHistory(String adId) {
        return getHistoryByAdIdAndType(adId, "STATUS_CHANGED");
    }

    public List<AdHistory> getPlacementHistory(String adId) {
        List<AdHistory> placementHistories = new java.util.ArrayList<>();
        placementHistories.addAll(getHistoryByAdIdAndType(adId, "PLACEMENT_CREATED"));
        placementHistories.addAll(getHistoryByAdIdAndType(adId, "PLACEMENT_STARTED"));
        placementHistories.addAll(getHistoryByAdIdAndType(adId, "PLACEMENT_PAUSED"));
        placementHistories.sort((h1, h2) -> h2.getCreatedAt().compareTo(h1.getCreatedAt()));
        return placementHistories;
    }

    public List<AdHistory> getBudgetHistory(String adId) {
        List<AdHistory> budgetHistories = new java.util.ArrayList<>();
        budgetHistories.addAll(getHistoryByAdIdAndType(adId, "BUDGET_CREATED"));
        budgetHistories.addAll(getHistoryByAdIdAndType(adId, "BUDGET_EXHAUSTED"));
        budgetHistories.addAll(getHistoryByAdIdAndType(adId, "BUDGET_THRESHOLD_REACHED"));
        budgetHistories.sort((h1, h2) -> h2.getCreatedAt().compareTo(h1.getCreatedAt()));
        return budgetHistories;
    }

    public List<AdHistory> getReportHistory(String adId) {
        return getHistoryByAdIdAndType(adId, "REPORT_GENERATED");
    }

    public List<AdHistory> getAnalysisHistory(String adId) {
        return getHistoryByAdIdAndType(adId, "AD_ANALYZED");
    }

    public List<AdHistory> getReviewHistory(String adId) {
        return getHistoryByAdIdAndType(adId, "AD_REVIEWED");
    }
}
