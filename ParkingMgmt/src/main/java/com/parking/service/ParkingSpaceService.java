package com.parking.service;

import com.parking.entity.ParkingLot;
import com.parking.entity.ParkingSpace;
import com.parking.entity.ParkingSpaceTypeConfig;
import com.parking.entity.VehicleTypeConfig;
import com.parking.exception.ParkingException;
import com.parking.repository.ParkingSpaceRepository;
import com.parking.repository.ParkingSpaceTypeConfigRepository;
import com.parking.repository.VehicleTypeConfigRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ParkingSpaceService {

    @Autowired
    private ParkingSpaceRepository parkingSpaceRepository;

    @Autowired
    private ParkingLotService parkingLotService;

    @Autowired
    private VehicleTypeConfigRepository vehicleTypeConfigRepository;

    @Autowired
    private ParkingSpaceTypeConfigRepository parkingSpaceTypeConfigRepository;

    private final Map<String, LockInfo> lockMap = new ConcurrentHashMap<>();
    private final Map<String, VehicleTypeConfig> vehicleTypeCache = new ConcurrentHashMap<>();
    private final Map<String, ParkingSpaceTypeConfig> spaceTypeCache = new ConcurrentHashMap<>();

    public static class LockInfo {
        String vehicleType;
        LocalDateTime lockTime;
        int timeoutSeconds;

        public LockInfo(String vehicleType, LocalDateTime lockTime, int timeoutSeconds) {
            this.vehicleType = vehicleType;
            this.lockTime = lockTime;
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isExpired() {
            return Duration.between(lockTime, LocalDateTime.now()).getSeconds() > timeoutSeconds;
        }
    }

    public int getLockTimeoutByVehicleType(String vehicleType) {
        if (vehicleType == null || vehicleType.trim().isEmpty()) {
            return getDefaultLockTimeout();
        }

        String key = vehicleType.toLowerCase().trim();
        VehicleTypeConfig cached = vehicleTypeCache.get(key);
        
        if (cached != null && cached.getEnabled()) {
            return cached.getLockTimeoutSeconds();
        }

        Optional<VehicleTypeConfig> config = vehicleTypeConfigRepository.findEnabledByVehicleType(key);
        if (config.isPresent()) {
            vehicleTypeCache.put(key, config.get());
            return config.get().getLockTimeoutSeconds();
        }

        return getDefaultLockTimeout();
    }

    private int getDefaultLockTimeout() {
        Optional<VehicleTypeConfig> defaultConfig = vehicleTypeConfigRepository.findEnabledByVehicleType("standard");
        return defaultConfig.map(VehicleTypeConfig::getLockTimeoutSeconds).orElse(120);
    }

    public VehicleTypeConfig getVehicleTypeConfig(String vehicleType) {
        if (vehicleType == null) return null;
        String key = vehicleType.toLowerCase().trim();
        return vehicleTypeConfigRepository.findByVehicleType(key).orElse(null);
    }

    @Transactional
    public VehicleTypeConfig createOrUpdateVehicleTypeConfig(String vehicleType, String displayName, 
                                                             int lockTimeoutSeconds, int priority,
                                                             String description) {
        String key = vehicleType.toLowerCase().trim();
        VehicleTypeConfig config = vehicleTypeConfigRepository.findByVehicleType(key)
                .orElse(new VehicleTypeConfig());

        config.setVehicleType(key);
        config.setDisplayName(displayName);
        config.setLockTimeoutSeconds(lockTimeoutSeconds);
        config.setPriority(priority);
        config.setDescription(description);
        config.setEnabled(true);

        VehicleTypeConfig saved = vehicleTypeConfigRepository.save(config);
        vehicleTypeCache.put(key, saved);
        
        return saved;
    }

    public List<VehicleTypeConfig> getAllVehicleTypeConfigs() {
        return vehicleTypeConfigRepository.findByEnabledTrue();
    }

    public ParkingSpaceTypeConfig getSpaceTypeConfig(String spaceType) {
        if (spaceType == null) return null;
        String key = spaceType.toLowerCase().trim();
        
        ParkingSpaceTypeConfig cached = spaceTypeCache.get(key);
        if (cached != null && cached.getEnabled()) {
            return cached;
        }

        Optional<ParkingSpaceTypeConfig> config = parkingSpaceTypeConfigRepository.findEnabledBySpaceType(key);
        if (config.isPresent()) {
            spaceTypeCache.put(key, config.get());
            return config.get();
        }

        return null;
    }

    public boolean isValidSpaceType(String spaceType) {
        if (spaceType == null) return false;
        String key = spaceType.toLowerCase().trim();
        return parkingSpaceTypeConfigRepository.existsBySpaceType(key);
    }

    public double calculateSpacePrice(String spaceType, double basePrice) {
        ParkingSpaceTypeConfig config = getSpaceTypeConfig(spaceType);
        if (config != null && config.getBasePriceMultiplier() != null) {
            return basePrice * config.getBasePriceMultiplier();
        }
        return basePrice;
    }

    public boolean canReserveSpaceType(String spaceType) {
        ParkingSpaceTypeConfig config = getSpaceTypeConfig(spaceType);
        return config != null && config.getCanReserve();
    }

    public boolean isVehicleTypeAllowedForSpace(String vehicleType, String spaceType) {
        ParkingSpaceTypeConfig config = getSpaceTypeConfig(spaceType);
        if (config == null || config.getVehicleTypeRestriction() == null) {
            return true;
        }

        String restrictions = config.getVehicleTypeRestriction().toLowerCase();
        if (vehicleType == null) {
            return false;
        }
        return restrictions.contains(vehicleType.toLowerCase());
    }

    @Transactional
    public ParkingSpaceTypeConfig createOrUpdateSpaceTypeConfig(String spaceType, String displayName,
                                                                double basePriceMultiplier, boolean canReserve,
                                                                String vehicleTypeRestriction, String description) {
        String key = spaceType.toLowerCase().trim();
        ParkingSpaceTypeConfig config = parkingSpaceTypeConfigRepository.findBySpaceType(key)
                .orElse(new ParkingSpaceTypeConfig());

        config.setSpaceType(key);
        config.setDisplayName(displayName);
        config.setBasePriceMultiplier(basePriceMultiplier);
        config.setCanReserve(canReserve);
        config.setVehicleTypeRestriction(vehicleTypeRestriction);
        config.setDescription(description);
        config.setEnabled(true);

        ParkingSpaceTypeConfig saved = parkingSpaceTypeConfigRepository.save(config);
        spaceTypeCache.put(key, saved);
        
        return saved;
    }

    public List<ParkingSpaceTypeConfig> getAllSpaceTypeConfigs() {
        return parkingSpaceTypeConfigRepository.findByEnabledTrue();
    }

    @Transactional
    public boolean tryLockSpace(String spaceId, String vehicleType) {
        ParkingSpace space = parkingSpaceRepository.findBySpaceIdWithLock(spaceId)
                .orElseThrow(() -> new ParkingException(404, "车位不存在: " + spaceId));

        if (!"available".equals(space.getSpaceStatus())) {
            return false;
        }

        LockInfo existingLock = lockMap.get(spaceId);
        if (existingLock != null && !existingLock.isExpired()) {
            return false;
        }

        int timeout = getLockTimeoutByVehicleType(vehicleType);
        lockMap.put(spaceId, new LockInfo(vehicleType, LocalDateTime.now(), timeout));

        return true;
    }

    @Transactional
    public void releaseLock(String spaceId) {
        lockMap.remove(spaceId);
    }

    @Transactional
    public ParkingSpace confirmLockAndOccupy(String spaceId, String vehicleType) {
        LockInfo lockInfo = lockMap.get(spaceId);
        if (lockInfo == null) {
            throw new ParkingException(400, "车位未锁定，无法确认占用");
        }

        if (lockInfo.isExpired()) {
            lockMap.remove(spaceId);
            throw new ParkingException(400, "车位锁定已超时");
        }

        ParkingSpace space = parkingSpaceRepository.findBySpaceIdWithLock(spaceId)
                .orElseThrow(() -> new ParkingException(404, "车位不存在: " + spaceId));

        if (!"available".equals(space.getSpaceStatus())) {
            throw new ParkingException(400, "车位已被占用");
        }

        space.setSpaceStatus("occupied");
        space.setOccupiedTime(LocalDateTime.now());

        lockMap.remove(spaceId);

        return parkingSpaceRepository.save(space);
    }

    public LockInfo getLockInfo(String spaceId) {
        return lockMap.get(spaceId);
    }

    public boolean isSpaceLocked(String spaceId) {
        LockInfo lockInfo = lockMap.get(spaceId);
        return lockInfo != null && !lockInfo.isExpired();
    }

    @Transactional
    public ParkingSpace createParkingSpace(String parkingId, String spaceNumber, String spaceType, double spacePrice) {
        ParkingLot parkingLot = parkingLotService.getParkingLotById(parkingId);

        String effectiveSpaceType = spaceType;
        if (effectiveSpaceType != null && !isValidSpaceType(effectiveSpaceType)) {
            effectiveSpaceType = "standard";
        }

        ParkingSpace space = new ParkingSpace();
        space.setSpaceId(IdGenerator.generateSpaceId());
        space.setParkingLot(parkingLot);
        space.setSpaceNumber(spaceNumber);
        space.setSpaceType(effectiveSpaceType != null ? effectiveSpaceType : "standard");
        space.setSpaceStatus("available");
        
        double basePrice = spacePrice > 0 ? spacePrice : parkingLot.getHourlyRate();
        double calculatedPrice = calculateSpacePrice(effectiveSpaceType, basePrice);
        space.setSpacePrice(calculatedPrice);

        return parkingSpaceRepository.save(space);
    }

    public ParkingSpace getParkingSpaceById(String spaceId) {
        return parkingSpaceRepository.findBySpaceId(spaceId)
                .orElseThrow(() -> new ParkingException(404, "车位不存在: " + spaceId));
    }

    public List<ParkingSpace> getAvailableSpaces(String parkingId) {
        return parkingSpaceRepository.findAvailableSpacesByParkingId(parkingId);
    }

    public List<ParkingSpace> getAvailableSpacesByType(String parkingId, String spaceType) {
        if (spaceType == null) {
            return getAvailableSpaces(parkingId);
        }
        return parkingSpaceRepository.findAvailableSpacesByParkingIdAndSpaceType(parkingId, spaceType);
    }

    public long countAvailableSpaces(String parkingId) {
        return parkingSpaceRepository.countAvailableSpaces(parkingId);
    }

    public long countAvailableSpacesByType(String parkingId, String spaceType) {
        if (spaceType == null) {
            return countAvailableSpaces(parkingId);
        }
        return parkingSpaceRepository.countAvailableSpacesByType(parkingId, spaceType);
    }

    public long countTotalSpaces(String parkingId) {
        return parkingSpaceRepository.countTotalSpaces(parkingId);
    }

    public long countTotalSpacesByType(String parkingId, String spaceType) {
        if (spaceType == null) {
            return countTotalSpaces(parkingId);
        }
        return parkingSpaceRepository.countTotalSpacesByType(parkingId, spaceType);
    }

    public List<ParkingSpace> getAllSpaces() {
        return parkingSpaceRepository.findAll();
    }

    @Transactional
    public ParkingSpace allocateSpace(String parkingId) {
        return allocateSpaceByType(parkingId, null, null);
    }

    @Transactional
    public ParkingSpace allocateSpaceByType(String parkingId, String spaceType, String vehicleType) {
        List<ParkingSpace> availableSpaces;
        
        if (spaceType != null) {
            availableSpaces = getAvailableSpacesByType(parkingId, spaceType);
        } else if (vehicleType != null) {
            availableSpaces = findSuitableSpaces(parkingId, vehicleType);
        } else {
            availableSpaces = getAvailableSpaces(parkingId);
        }

        if (availableSpaces.isEmpty()) {
            throw new ParkingException(400, "停车场暂无可用车位");
        }

        for (ParkingSpace space : availableSpaces) {
            if (vehicleType != null && !isVehicleTypeAllowedForSpace(vehicleType, space.getSpaceType())) {
                continue;
            }

            if (tryLockSpace(space.getSpaceId(), vehicleType)) {
                return confirmLockAndOccupy(space.getSpaceId(), vehicleType);
            }
        }

        throw new ParkingException(400, "暂无适合的可用车位");
    }

    private List<ParkingSpace> findSuitableSpaces(String parkingId, String vehicleType) {
        List<ParkingSpace> allAvailable = getAvailableSpaces(parkingId);
        return allAvailable.stream()
                .filter(space -> isVehicleTypeAllowedForSpace(vehicleType, space.getSpaceType()))
                .toList();
    }

    @Transactional
    public ParkingSpace updateSpaceStatus(String spaceId, String status) {
        ParkingSpace space = getParkingSpaceById(spaceId);
        space.setSpaceStatus(status);

        if ("occupied".equals(status)) {
            space.setOccupiedTime(LocalDateTime.now());
        } else if ("available".equals(status)) {
            space.setOccupiedTime(null);
        }

        return parkingSpaceRepository.save(space);
    }

    @Transactional
    public ParkingSpace updateSpaceStatusWithLock(String spaceId, String status) {
        ParkingSpace space = parkingSpaceRepository.findBySpaceIdWithLock(spaceId)
                .orElseThrow(() -> new ParkingException(404, "车位不存在: " + spaceId));

        if ("occupied".equals(space.getSpaceStatus()) && "occupied".equals(status)) {
            throw new ParkingException(400, "车位已被占用");
        }

        space.setSpaceStatus(status);

        if ("occupied".equals(status)) {
            space.setOccupiedTime(LocalDateTime.now());
        } else if ("available".equals(status)) {
            space.setOccupiedTime(null);
        }

        return parkingSpaceRepository.save(space);
    }

    @Transactional
    public void deleteParkingSpace(String spaceId) {
        ParkingSpace space = getParkingSpaceById(spaceId);
        parkingSpaceRepository.delete(space);
    }

    public void refreshVehicleTypeCache() {
        vehicleTypeCache.clear();
        List<VehicleTypeConfig> configs = vehicleTypeConfigRepository.findByEnabledTrue();
        for (VehicleTypeConfig config : configs) {
            vehicleTypeCache.put(config.getVehicleType().toLowerCase(), config);
        }
    }

    public void refreshSpaceTypeCache() {
        spaceTypeCache.clear();
        List<ParkingSpaceTypeConfig> configs = parkingSpaceTypeConfigRepository.findByEnabledTrue();
        for (ParkingSpaceTypeConfig config : configs) {
            spaceTypeCache.put(config.getSpaceType().toLowerCase(), config);
        }
    }
}
