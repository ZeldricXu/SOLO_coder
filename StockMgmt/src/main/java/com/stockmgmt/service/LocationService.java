package com.stockmgmt.service;

import com.stockmgmt.entity.StockLocation;
import com.stockmgmt.exception.BusinessException;
import com.stockmgmt.repository.StockLocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class LocationService {

    private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

    @Autowired
    private StockLocationRepository locationRepository;

    @Transactional(rollbackFor = Exception.class)
    public StockLocation createLocation(StockLocation location) {
        logger.info("创建库位，编码: {}", location.getLocationCode());

        if (locationRepository.findByLocationCode(location.getLocationCode()).isPresent()) {
            throw BusinessException.of("库位编码已存在: " + location.getLocationCode());
        }

        if (location.getStatus() == null) {
            location.setStatus("active");
        }

        StockLocation saved = locationRepository.save(location);
        logger.info("库位创建成功，ID: {}", saved.getLocationId());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockLocation getOrCreateLocation(String warehouseId, String locationCode) {
        logger.info("获取或创建库位，仓库: {}, 编码: {}", warehouseId, locationCode);

        Optional<StockLocation> existingLocation = locationRepository.findByWarehouseIdAndLocationCode(
                warehouseId != null ? warehouseId : "warehouse_main", locationCode);

        if (existingLocation.isPresent()) {
            return existingLocation.get();
        }

        StockLocation location = new StockLocation();
        location.setWarehouseId(warehouseId != null ? warehouseId : "warehouse_main");
        location.setLocationCode(locationCode);
        location.setLocationName(locationCode);
        location.setStatus("active");

        return locationRepository.save(location);
    }

    public StockLocation getLocationById(String locationId) {
        return locationRepository.findById(locationId)
                .orElseThrow(() -> BusinessException.of("库位不存在: " + locationId));
    }

    public StockLocation getLocationByCode(String locationCode) {
        return locationRepository.findByLocationCode(locationCode)
                .orElseThrow(() -> BusinessException.of("库位不存在: " + locationCode));
    }

    public List<StockLocation> getLocationsByWarehouse(String warehouseId) {
        return locationRepository.findByWarehouseId(warehouseId);
    }

    public List<StockLocation> getActiveLocations(String warehouseId) {
        return locationRepository.findByWarehouseIdAndStatus(warehouseId, "active");
    }

    @Transactional(rollbackFor = Exception.class)
    public StockLocation updateLocation(String locationId, StockLocation update) {
        StockLocation location = getLocationById(locationId);

        if (update.getLocationName() != null) {
            location.setLocationName(update.getLocationName());
        }
        if (update.getZone() != null) {
            location.setZone(update.getZone());
        }
        if (update.getAisle() != null) {
            location.setAisle(update.getAisle());
        }
        if (update.getRack() != null) {
            location.setRack(update.getRack());
        }
        if (update.getLevel() != null) {
            location.setLevel(update.getLevel());
        }
        if (update.getCapacity() != null) {
            location.setCapacity(update.getCapacity());
        }
        if (update.getStatus() != null) {
            location.setStatus(update.getStatus());
        }

        return locationRepository.save(location);
    }
}
