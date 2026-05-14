package com.assetmanage.service;

import com.assetmanage.common.AssetLockManager;
import com.assetmanage.common.IdGenerator;
import com.assetmanage.dto.AssetUseRequest;
import com.assetmanage.dto.AssetReturnRequest;
import com.assetmanage.dto.UseResponse;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.UsageRecord;
import com.assetmanage.enums.AssetStatus;
import com.assetmanage.enums.UsageStatus;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final UsageRecordRepository usageRecordRepository;
    private final AssetService assetService;
    private final HistoryService historyService;
    private final MaintenanceService maintenanceService;
    private final AnalysisService analysisService;
    private final AssetLockManager lockManager;

    @Transactional
    public UseResponse useAsset(AssetUseRequest request) {
        String assetId = request.getAssetId();
        String userId = request.getUserId();

        Asset asset = assetService.getAssetById(assetId);

        if (!lockManager.tryLock(assetId, userId, asset.getAssetType(), 
                asset.getCurrentValue() != null ? asset.getCurrentValue() : asset.getPurchasePrice(),
                asset.getAssetCategory())) {
            throw new BusinessException("领用失败：资产正被其他用户锁定，请稍后重试");
        }

        try {
            validateAssetStatus(asset);

            UsageRecord usageRecord = createUsageRecord(request);

            asset.setAssetStatus(AssetStatus.IN_USE.getCode());
            asset.setCurrentUserId(userId);
            assetService.save(asset);

            maintenanceService.adjustMaintenancePlan(assetId);

            historyService.recordHistory(assetId, "use",
                    "资产领用，领用人: " + userId, request.getOperatorId());

            analysisService.updateStatistics();

            log.info("资产领用成功: assetId={}, usageId={}, type={}", assetId, usageRecord.getUsageId(), asset.getAssetType());

            return new UseResponse(usageRecord.getUsageId(), usageRecord.getUsageStatus());
        } finally {
            lockManager.releaseLock(assetId, userId, asset.getAssetType(),
                    asset.getCurrentValue() != null ? asset.getCurrentValue() : asset.getPurchasePrice(),
                    asset.getAssetCategory());
        }
    }

    private void validateAssetStatus(Asset asset) {
        String status = asset.getAssetStatus();
        if (AssetStatus.IN_USE.getCode().equals(status)) {
            throw new BusinessException("领用失败：资产已被领用");
        }
        if (AssetStatus.MAINTENANCE.getCode().equals(status)) {
            throw new BusinessException("领用失败：资产正在维护中");
        }
        if (AssetStatus.SCRAPPED.getCode().equals(status)) {
            throw new BusinessException("领用失败：资产已报废");
        }
    }

    private UsageRecord createUsageRecord(AssetUseRequest request) {
        UsageRecord usageRecord = new UsageRecord();
        usageRecord.setUsageId(IdGenerator.generateUsageId());
        usageRecord.setAssetId(request.getAssetId());
        usageRecord.setUserId(request.getUserId());
        usageRecord.setUsageType(request.getUsageType());
        usageRecord.setExpectedReturn(request.getExpectedReturn());
        usageRecord.setUsageStatus(UsageStatus.ACTIVE.getCode());
        return usageRecordRepository.save(usageRecord);
    }

    @Transactional
    public void returnAsset(AssetReturnRequest request) {
        String assetId = request.getAssetId();
        String operatorId = request.getOperatorId();

        Optional<UsageRecord> activeUsageOpt = usageRecordRepository.findActiveByAssetId(assetId);

        if (activeUsageOpt.isEmpty()) {
            throw new BusinessException("该资产没有活跃的领用记录");
        }

        UsageRecord usageRecord = activeUsageOpt.get();
        usageRecord.setUsageStatus(UsageStatus.RETURNED.getCode());
        usageRecord.setActualReturn(LocalDateTime.now());
        usageRecordRepository.save(usageRecord);

        Asset asset = assetService.getAssetById(assetId);
        asset.setAssetStatus(AssetStatus.IDLE.getCode());
        asset.setCurrentUserId(null);
        assetService.save(asset);

        historyService.recordHistory(assetId, "return",
                "资产归还" + (request.getReturnNote() != null ? ": " + request.getReturnNote() : ""),
                operatorId);

        analysisService.updateStatistics();

        log.info("资产归还成功: assetId={}, usageId={}", assetId, usageRecord.getUsageId());
    }

    public List<UsageRecord> getUsageRecordsByAsset(String assetId) {
        return usageRecordRepository.findByAssetIdOrderByUsageStartDesc(assetId);
    }

    public List<UsageRecord> getUsageRecordsByUser(String userId) {
        return usageRecordRepository.findByUserId(userId);
    }

    public List<UsageRecord> getActiveUsageRecords() {
        return usageRecordRepository.findByUsageStatus(UsageStatus.ACTIVE.getCode());
    }

    public Optional<UsageRecord> getActiveUsageByAsset(String assetId) {
        return usageRecordRepository.findActiveByAssetId(assetId);
    }

    public List<UsageRecord> getAllUsageRecords() {
        return usageRecordRepository.findAll();
    }

    public com.assetmanage.config.LockConfigProperties.LockConfig getLockConfigForAsset(Asset asset) {
        return lockManager.getLockConfigForAsset(
                asset.getAssetType(),
                asset.getCurrentValue() != null ? asset.getCurrentValue() : asset.getPurchasePrice()
        );
    }
}
