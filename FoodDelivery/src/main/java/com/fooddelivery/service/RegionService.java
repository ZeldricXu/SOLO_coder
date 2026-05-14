package com.fooddelivery.service;

import com.fooddelivery.entity.Region;
import com.fooddelivery.repository.RegionRepository;
import com.fooddelivery.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RegionService {

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Region createRegion(Region region) {
        region.setRegionId(IdGenerator.generateRegionId());
        Region saved = regionRepository.save(region);
        historyService.recordHistory("region", saved.getRegionId(), "create", "创建区域：" + saved.getRegionName());
        return saved;
    }

    public Optional<Region> getRegionById(String regionId) {
        return regionRepository.findById(regionId);
    }

    public Optional<Region> getRegionByName(String regionName) {
        return regionRepository.findByRegionName(regionName);
    }

    public List<Region> getAllRegions() {
        return regionRepository.findAll();
    }

    @Transactional
    public Region updateRegion(String regionId, Region region) {
        Region existing = regionRepository.findById(regionId)
                .orElseThrow(() -> new RuntimeException("区域不存在"));
        existing.setRegionName(region.getRegionName() != null ? region.getRegionName() : existing.getRegionName());
        existing.setRegionDesc(region.getRegionDesc() != null ? region.getRegionDesc() : existing.getRegionDesc());
        existing.setRegionBoundaries(region.getRegionBoundaries() != null ? region.getRegionBoundaries() : existing.getRegionBoundaries());
        Region saved = regionRepository.save(existing);
        historyService.recordHistory("region", saved.getRegionId(), "update", "更新区域信息");
        return saved;
    }

    @Transactional
    public void deleteRegion(String regionId) {
        Region region = regionRepository.findById(regionId)
                .orElseThrow(() -> new RuntimeException("区域不存在"));
        regionRepository.delete(region);
        historyService.recordHistory("region", regionId, "delete", "删除区域：" + region.getRegionName());
    }

    public String matchRegionByAddress(String address) {
        List<Region> regions = regionRepository.findAll();
        for (Region region : regions) {
            if (address.contains(region.getRegionName())) {
                return region.getRegionName();
            }
        }
        if (!regions.isEmpty()) {
            return regions.get(0).getRegionName();
        }
        return "default";
    }
}
