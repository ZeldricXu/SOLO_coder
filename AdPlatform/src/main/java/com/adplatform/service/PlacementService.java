package com.adplatform.service;

import com.adplatform.dto.PlacementRequest;
import com.adplatform.dto.PlacementResponse;
import com.adplatform.entity.AdHistory;
import com.adplatform.entity.AdInfo;
import com.adplatform.entity.AdPlacement;
import com.adplatform.exception.BusinessException;
import com.adplatform.queue.EffectEventQueue;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.repository.AdPlacementRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PlacementService {
    private static final Logger logger = LoggerFactory.getLogger(PlacementService.class);
    
    private final AdPlacementRepository adPlacementRepository;
    private final AdInfoRepository adInfoRepository;
    private final AdHistoryRepository adHistoryRepository;
    private final StatusService statusService;
    private final BudgetService budgetService;
    private final TargetingService targetingService;
    private final EffectEventQueue effectEventQueue;

    public PlacementService(AdPlacementRepository adPlacementRepository,
                           AdInfoRepository adInfoRepository,
                           AdHistoryRepository adHistoryRepository,
                           StatusService statusService,
                           BudgetService budgetService,
                           TargetingService targetingService,
                           EffectEventQueue effectEventQueue) {
        this.adPlacementRepository = adPlacementRepository;
        this.adInfoRepository = adInfoRepository;
        this.adHistoryRepository = adHistoryRepository;
        this.statusService = statusService;
        this.budgetService = budgetService;
        this.targetingService = targetingService;
        this.effectEventQueue = effectEventQueue;
    }

    @Transactional
    public PlacementResponse createPlacement(PlacementRequest request) {
        String adId = request.getAdId();
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        
        if (adInfoOpt.isEmpty()) {
            throw new BusinessException(404, "广告不存在");
        }

        AdInfo adInfo = adInfoOpt.get();
        String adStatus = adInfo.getAdStatus();
        
        if (!"approved".equals(adStatus) && !"paused".equals(adStatus)) {
            throw new BusinessException(400, "广告状态不允许投放，当前状态: " + adStatus);
        }

        validatePlacementRequest(request);

        if (request.getTargetType() != null && request.getTargetConditions() != null) {
            targetingService.createTargeting(adId, request.getTargetType(), request.getTargetConditions());
        }

        if (request.getBudgetAmount() != null) {
            budgetService.createBudget(adId, request.getBudgetType(), request.getBudgetAmount(), null);
        }

        LocalDateTime placementStart = request.getPlacementStart() != null 
                ? request.getPlacementStart() 
                : LocalDateTime.now();
        LocalDateTime placementEnd = request.getPlacementEnd() != null 
                ? request.getPlacementEnd() 
                : LocalDateTime.now().plusDays(30);

        String placementId = IdGenerator.generateId("placement");
        AdPlacement placement = AdPlacement.builder()
                .placementId(placementId)
                .adId(adId)
                .placementChannel(request.getPlacementChannel() != null ? request.getPlacementChannel() : "mobile_app")
                .placementPosition(request.getPlacementPosition() != null ? request.getPlacementPosition() : "home_banner")
                .placementStart(placementStart)
                .placementEnd(placementEnd)
                .placementStatus("active")
                .build();
        
        adPlacementRepository.save(placement);
        logger.info("投放配置创建成功: adId={}, placementId={}", adId, placementId);

        statusService.updateStatus(adId, "running", "启动投放");

        recordPlacementHistory(adId, placement, "PLACEMENT_CREATED");

        return PlacementResponse.builder()
                .placementId(placementId)
                .status("active")
                .build();
    }

    @Transactional
    public boolean startPlacement(String adId) {
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        if (adInfoOpt.isEmpty()) {
            throw new BusinessException(404, "广告不存在");
        }

        AdInfo adInfo = adInfoOpt.get();
        if (!statusService.isAdRunnable(adId)) {
            throw new BusinessException(400, "广告状态不允许启动投放");
        }

        if (!budgetService.hasEnoughBudget(adId, java.math.BigDecimal.ONE)) {
            throw new BusinessException(400, "广告预算不足");
        }

        List<AdPlacement> placements = adPlacementRepository.findByAdId(adId);
        for (AdPlacement placement : placements) {
            placement.setPlacementStatus("active");
            adPlacementRepository.save(placement);
        }

        statusService.updateStatus(adId, "running", "启动投放");
        logger.info("广告投放启动: adId={}", adId);
        
        recordHistory(adId, "PLACEMENT_STARTED", new HashMap<>());
        return true;
    }

    @Transactional
    public boolean stopPlacement(String adId) {
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        if (adInfoOpt.isEmpty()) {
            throw new BusinessException(404, "广告不存在");
        }

        if (!statusService.isAdRunning(adId)) {
            throw new BusinessException(400, "广告未在投放中");
        }

        List<AdPlacement> placements = adPlacementRepository.findByAdId(adId);
        for (AdPlacement placement : placements) {
            placement.setPlacementStatus("inactive");
            adPlacementRepository.save(placement);
        }

        statusService.updateStatus(adId, "paused", "暂停投放");
        logger.info("广告投放暂停: adId={}", adId);
        
        recordHistory(adId, "PLACEMENT_PAUSED", new HashMap<>());
        return true;
    }

    public List<AdPlacement> getPlacementsByAdId(String adId) {
        return adPlacementRepository.findByAdId(adId);
    }

    public Optional<AdPlacement> getPlacementById(String placementId) {
        return adPlacementRepository.findByPlacementId(placementId);
    }

    private void validatePlacementRequest(PlacementRequest request) {
        if (request.getAdId() == null || request.getAdId().isEmpty()) {
            throw new BusinessException(400, "广告ID不能为空");
        }
        if (request.getPlacementChannel() == null || request.getPlacementChannel().isEmpty()) {
            throw new BusinessException(400, "投放渠道不能为空");
        }
    }

    private void recordPlacementHistory(String adId, AdPlacement placement, String historyType) {
        Map<String, Object> historyData = new HashMap<>();
        historyData.put("adId", adId);
        historyData.put("placementId", placement.getPlacementId());
        historyData.put("placementChannel", placement.getPlacementChannel());
        historyData.put("placementPosition", placement.getPlacementPosition());
        historyData.put("placementStart", placement.getPlacementStart());
        historyData.put("placementEnd", placement.getPlacementEnd());
        historyData.put("placementStatus", placement.getPlacementStatus());
        
        recordHistory(adId, historyType, historyData);
    }

    private void recordHistory(String adId, String historyType, Map<String, Object> historyData) {
        AdHistory history = AdHistory.builder()
                .historyId(IdGenerator.generateId("history"))
                .adId(adId)
                .historyType(historyType)
                .historyData(historyData)
                .build();
        adHistoryRepository.save(history);
    }
}
