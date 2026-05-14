package com.assetmanage.service;

import com.assetmanage.common.IdGenerator;
import com.assetmanage.dto.AssetRegisterRequest;
import com.assetmanage.entity.Asset;
import com.assetmanage.enums.AssetStatus;
import com.assetmanage.enums.DepreciationMethod;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository assetRepository;
    private final HistoryService historyService;

    @Transactional
    public String registerAsset(AssetRegisterRequest request) {
        String assetId = IdGenerator.generateAssetId();
        
        Asset asset = new Asset();
        asset.setAssetId(assetId);
        asset.setAssetName(request.getAssetName());
        asset.setAssetType(request.getAssetType());
        asset.setAssetCategory(request.getAssetCategory());
        asset.setAssetModel(request.getAssetModel());
        asset.setAssetSn(request.getAssetSn());
        asset.setPurchaseDate(request.getPurchaseDate());
        asset.setPurchasePrice(request.getPurchasePrice());
        asset.setCurrentValue(request.getPurchasePrice());
        asset.setAccumulatedDepreciation(BigDecimal.ZERO);
        
        if (request.getDepreciationMethod() != null) {
            asset.setDepreciationMethod(request.getDepreciationMethod());
        } else {
            asset.setDepreciationMethod(DepreciationMethod.STRAIGHT_LINE.getCode());
        }
        
        if (request.getDepreciationRate() != null) {
            asset.setDepreciationRate(request.getDepreciationRate());
        } else {
            asset.setDepreciationRate(new BigDecimal("0.2"));
        }
        
        if (request.getUsefulLife() != null) {
            asset.setUsefulLife(request.getUsefulLife());
        } else {
            asset.setUsefulLife(5);
        }
        
        asset.setAssetStatus(AssetStatus.IDLE.getCode());
        asset.setLocation(request.getLocation());
        asset.setDepartment(request.getDepartment());
        
        assetRepository.save(asset);
        
        historyService.recordHistory(assetId, "register", 
                "资产登记: " + request.getAssetName(), null);
        
        log.info("资产登记成功，资产ID: {}", assetId);
        return assetId;
    }

    public Asset getAssetById(String assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new BusinessException("资产不存在: " + assetId));
    }

    public List<Asset> getAllAssets() {
        return assetRepository.findAll();
    }

    public List<Asset> getAssetsByStatus(String status) {
        return assetRepository.findByAssetStatus(status);
    }

    public List<Asset> getAssetsByType(String type) {
        return assetRepository.findByAssetType(type);
    }

    public List<Asset> getAssetsByCategory(String category) {
        return assetRepository.findByAssetCategory(category);
    }

    public List<Asset> getAssetsByDepartment(String department) {
        return assetRepository.findByDepartment(department);
    }

    public List<Asset> getActiveAssets() {
        return assetRepository.findActiveAssets();
    }

    @Transactional
    public Asset updateAssetStatus(String assetId, String status) {
        Asset asset = getAssetById(assetId);
        asset.setAssetStatus(status);
        return assetRepository.save(asset);
    }

    @Transactional
    public void updateAssetValue(String assetId, BigDecimal currentValue, BigDecimal accumulatedDepreciation) {
        Asset asset = getAssetById(assetId);
        asset.setCurrentValue(currentValue);
        asset.setAccumulatedDepreciation(accumulatedDepreciation);
        assetRepository.save(asset);
    }

    public Optional<Asset> findById(String assetId) {
        return assetRepository.findById(assetId);
    }

    public void save(Asset asset) {
        assetRepository.save(asset);
    }

    public long countByStatus(String status) {
        return assetRepository.countByStatus(status);
    }

    public BigDecimal sumCurrentValue() {
        BigDecimal sum = assetRepository.sumCurrentValue();
        return sum != null ? sum : BigDecimal.ZERO;
    }
}
