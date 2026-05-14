package com.assetinventory.service;

import com.assetinventory.entity.Asset;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.AssetRepository;
import com.assetinventory.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AssetService {

    private final AssetRepository assetRepository;

    @Autowired
    public AssetService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public Asset createAsset(String assetName, String assetCategory, int assetQuantity,
                             String assetLocation, double assetValue) {
        Asset asset = new Asset();
        asset.setAssetId(IdGenerator.generateAssetId());
        asset.setAssetName(assetName);
        asset.setAssetCategory(assetCategory);
        asset.setAssetQuantity(assetQuantity);
        asset.setAssetLocation(assetLocation);
        asset.setAssetStatus("uncounted");
        asset.setAssetValue(assetValue);
        asset.setRegisteredAt(IdGenerator.now());
        asset.setLastCountedAt(null);

        return assetRepository.save(asset);
    }

    public List<Asset> getAllAssets() {
        return assetRepository.findAll();
    }

    public List<Asset> getAssetsByStatus(String status) {
        return assetRepository.findByAssetStatus(status);
    }

    public List<Asset> getAssetsByCategory(String category) {
        return assetRepository.findByAssetCategory(category);
    }

    public Optional<Asset> getAssetById(String assetId) {
        return assetRepository.findByAssetId(assetId);
    }

    public Asset getAssetByIdOrThrow(String assetId) {
        return assetRepository.findByAssetId(assetId)
                .orElseThrow(() -> new InventoryException(404, "资产不存在: " + assetId));
    }

    public Asset updateAssetStatus(String assetId, String status) {
        Asset asset = getAssetByIdOrThrow(assetId);
        asset.setAssetStatus(status);
        return assetRepository.save(asset);
    }

    public Asset updateAssetQuantity(String assetId, int newQuantity) {
        Asset asset = getAssetByIdOrThrow(assetId);
        asset.setAssetQuantity(newQuantity);
        return assetRepository.save(asset);
    }

    public Asset updateAssetLocation(String assetId, String newLocation) {
        Asset asset = getAssetByIdOrThrow(assetId);
        asset.setAssetLocation(newLocation);
        return assetRepository.save(asset);
    }

    public Asset updateLastCountedAt(String assetId, Instant countedAt) {
        Asset asset = getAssetByIdOrThrow(assetId);
        asset.setLastCountedAt(countedAt);
        return assetRepository.save(asset);
    }
}
