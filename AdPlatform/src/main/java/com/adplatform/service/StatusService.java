package com.adplatform.service;

import com.adplatform.entity.AdHistory;
import com.adplatform.entity.AdInfo;
import com.adplatform.exception.BusinessException;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class StatusService {
    private static final Logger logger = LoggerFactory.getLogger(StatusService.class);
    
    private final AdInfoRepository adInfoRepository;
    private final AdHistoryRepository adHistoryRepository;

    public StatusService(AdInfoRepository adInfoRepository, 
                         AdHistoryRepository adHistoryRepository) {
        this.adInfoRepository = adInfoRepository;
        this.adHistoryRepository = adHistoryRepository;
    }

    @Transactional
    public AdInfo updateStatus(String adId, String newStatus, String reason) {
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        if (adInfoOpt.isEmpty()) {
            throw new BusinessException(404, "广告不存在");
        }

        AdInfo adInfo = adInfoOpt.get();
        String oldStatus = adInfo.getAdStatus();
        
        if (!isValidStatusTransition(oldStatus, newStatus)) {
            throw new BusinessException(400, "无效的状态转换: " + oldStatus + " -> " + newStatus);
        }

        adInfo.setAdStatus(newStatus);
        adInfoRepository.save(adInfo);
        logger.info("广告状态更新: {} {} -> {}", adId, oldStatus, newStatus);

        recordHistory(adId, oldStatus, newStatus, reason);
        return adInfo;
    }

    public boolean isValidStatusTransition(String from, String to) {
        return switch (from) {
            case "pending" -> "approved".equals(to) || "rejected".equals(to);
            case "approved" -> "running".equals(to) || "paused".equals(to);
            case "running" -> "paused".equals(to) || "ended".equals(to);
            case "paused" -> "running".equals(to) || "ended".equals(to);
            case "rejected", "ended" -> false;
            default -> false;
        };
    }

    public String getAdStatus(String adId) {
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        if (adInfoOpt.isEmpty()) {
            throw new BusinessException(404, "广告不存在");
        }
        return adInfoOpt.get().getAdStatus();
    }

    public boolean isAdRunnable(String adId) {
        String status = getAdStatus(adId);
        return "approved".equals(status) || "paused".equals(status);
    }

    public boolean isAdRunning(String adId) {
        String status = getAdStatus(adId);
        return "running".equals(status);
    }

    private void recordHistory(String adId, String oldStatus, String newStatus, String reason) {
        Map<String, Object> historyData = new HashMap<>();
        historyData.put("adId", adId);
        historyData.put("oldStatus", oldStatus);
        historyData.put("newStatus", newStatus);
        historyData.put("reason", reason);
        
        AdHistory history = AdHistory.builder()
                .historyId(IdGenerator.generateId("history"))
                .adId(adId)
                .historyType("STATUS_CHANGED")
                .historyData(historyData)
                .build();
        adHistoryRepository.save(history);
    }
}
