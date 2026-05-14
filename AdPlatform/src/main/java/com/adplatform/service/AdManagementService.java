package com.adplatform.service;

import com.adplatform.dto.CreateAdRequest;
import com.adplatform.dto.CreateAdResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AdManagementService {
    private static final Logger logger = LoggerFactory.getLogger(AdManagementService.class);
    
    private final AdInfoRepository adInfoRepository;
    private final AdHistoryRepository adHistoryRepository;

    public AdManagementService(AdInfoRepository adInfoRepository, 
                                AdHistoryRepository adHistoryRepository) {
        this.adInfoRepository = adInfoRepository;
        this.adHistoryRepository = adHistoryRepository;
    }

    @Transactional
    public CreateAdResponse createAd(CreateAdRequest request) {
        if (request.getAdName() == null || request.getAdName().isEmpty()) {
            throw new BusinessException(400, "广告名称不能为空");
        }
        if (request.getAdType() == null || request.getAdType().isEmpty()) {
            throw new BusinessException(400, "广告类型不能为空");
        }
        if (request.getAdContent() == null || request.getAdContent().isEmpty()) {
            throw new BusinessException(400, "广告内容不能为空");
        }

        String adId = IdGenerator.generateId("ad");
        String advertiser = request.getAdvertiser() != null ? request.getAdvertiser() : "default_advertiser";
        
        AdInfo adInfo = AdInfo.builder()
                .adId(adId)
                .adName(request.getAdName())
                .adType(request.getAdType())
                .adContent(request.getAdContent())
                .adStatus("pending")
                .advertiser(advertiser)
                .build();
        
        adInfoRepository.save(adInfo);
        logger.info("广告创建成功: {}", adId);

        recordHistory(adId, "AD_CREATED", buildHistoryData(adInfo));

        return CreateAdResponse.builder()
                .adId(adId)
                .status("pending")
                .build();
    }

    @Transactional
    public AdInfo reviewAd(String adId, boolean approved, String reason) {
        Optional<AdInfo> adInfoOpt = adInfoRepository.findByAdId(adId);
        if (adInfoOpt.isEmpty()) {
            throw new BusinessException(404, "广告不存在");
        }

        AdInfo adInfo = adInfoOpt.get();
        if (!"pending".equals(adInfo.getAdStatus())) {
            throw new BusinessException(400, "广告不在待审核状态");
        }

        String newStatus = approved ? "approved" : "rejected";
        adInfo.setAdStatus(newStatus);
        adInfoRepository.save(adInfo);
        logger.info("广告审核完成: {}, 结果: {}", adId, newStatus);

        Map<String, Object> historyData = buildHistoryData(adInfo);
        historyData.put("reviewResult", newStatus);
        historyData.put("reviewReason", reason);
        recordHistory(adId, "AD_REVIEWED", historyData);

        return adInfo;
    }

    public Optional<AdInfo> getAdById(String adId) {
        return adInfoRepository.findByAdId(adId);
    }

    public List<AdInfo> getAdsByStatus(String status) {
        return adInfoRepository.findByAdStatus(status);
    }

    public List<AdInfo> getAdsByAdvertiser(String advertiser) {
        return adInfoRepository.findByAdvertiser(advertiser);
    }

    private Map<String, Object> buildHistoryData(AdInfo adInfo) {
        Map<String, Object> data = new HashMap<>();
        data.put("adId", adInfo.getAdId());
        data.put("adName", adInfo.getAdName());
        data.put("adType", adInfo.getAdType());
        data.put("adStatus", adInfo.getAdStatus());
        data.put("advertiser", adInfo.getAdvertiser());
        return data;
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
