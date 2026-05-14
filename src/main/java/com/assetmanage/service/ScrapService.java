package com.assetmanage.service;

import com.assetmanage.common.IdGenerator;
import com.assetmanage.dto.AssetScrapRequest;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.ScrapRecord;
import com.assetmanage.enums.AssetStatus;
import com.assetmanage.enums.ScrapStatus;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.ScrapRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScrapService {

    private final ScrapRecordRepository scrapRepository;
    private final AssetService assetService;
    private final HistoryService historyService;
    private final AnalysisService analysisService;

    @Transactional
    public String submitScrap(AssetScrapRequest request) {
        Asset asset = assetService.getAssetById(request.getAssetId());

        if (AssetStatus.SCRAPPED.getCode().equals(asset.getAssetStatus())) {
            throw new BusinessException("资产已报废");
        }

        ScrapRecord record = new ScrapRecord();
        record.setScrapId(IdGenerator.generateScrapId());
        record.setAssetId(request.getAssetId());
        record.setScrapReason(request.getScrapReason());
        record.setScrapStatus(ScrapStatus.APPROVED.getCode());
        
        if (request.getResidualValue() != null) {
            record.setResidualValue(request.getResidualValue());
        } else {
            record.setResidualValue(asset.getCurrentValue() != null ? asset.getCurrentValue() : BigDecimal.ZERO);
        }
        
        scrapRepository.save(record);

        asset.setAssetStatus(AssetStatus.SCRAPPED.getCode());
        assetService.save(asset);

        historyService.recordHistory(request.getAssetId(), "scrap",
                "资产报废: " + request.getScrapReason(), request.getOperatorId());

        analysisService.updateStatistics();

        log.info("资产报废成功: assetId={}, scrapId={}", request.getAssetId(), record.getScrapId());
        return record.getScrapId();
    }

    public ScrapRecord getScrapById(String scrapId) {
        return scrapRepository.findById(scrapId)
                .orElseThrow(() -> new BusinessException("报废记录不存在: " + scrapId));
    }

    public List<ScrapRecord> getScrapsByAsset(String assetId) {
        return scrapRepository.findByAssetId(assetId);
    }

    public List<ScrapRecord> getScrapsByStatus(String status) {
        return scrapRepository.findByScrapStatus(status);
    }

    public List<ScrapRecord> getAllScraps() {
        return scrapRepository.findAll();
    }
}
