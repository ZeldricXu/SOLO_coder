package com.parking.controller;

import com.parking.entity.PreCalculationConfig;
import com.parking.entity.ParkingSpaceTypeConfig;
import com.parking.entity.VehicleTypeConfig;
import com.parking.service.ParkingSpaceService;
import com.parking.service.SettlementService;
import com.parking.service.SettlementTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/configs")
public class ConfigController {

    @Autowired
    private ParkingSpaceService parkingSpaceService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementTaskService settlementTaskService;

    @GetMapping("/vehicle-types")
    public ResponseEntity<List<VehicleTypeConfig>> getAllVehicleTypeConfigs() {
        return ResponseEntity.ok(parkingSpaceService.getAllVehicleTypeConfigs());
    }

    @GetMapping("/vehicle-types/{vehicleType}")
    public ResponseEntity<VehicleTypeConfig> getVehicleTypeConfig(@PathVariable String vehicleType) {
        VehicleTypeConfig config = parkingSpaceService.getVehicleTypeConfig(vehicleType);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping("/vehicle-types")
    public ResponseEntity<VehicleTypeConfig> createOrUpdateVehicleTypeConfig(
            @RequestBody VehicleTypeConfigRequest request) {
        VehicleTypeConfig config = parkingSpaceService.createOrUpdateVehicleTypeConfig(
                request.getVehicleType(),
                request.getDisplayName(),
                request.getLockTimeoutSeconds(),
                request.getPriority(),
                request.getDescription()
        );
        return ResponseEntity.ok(config);
    }

    @GetMapping("/space-types")
    public ResponseEntity<List<ParkingSpaceTypeConfig>> getAllSpaceTypeConfigs() {
        return ResponseEntity.ok(parkingSpaceService.getAllSpaceTypeConfigs());
    }

    @GetMapping("/space-types/{spaceType}")
    public ResponseEntity<ParkingSpaceTypeConfig> getSpaceTypeConfig(@PathVariable String spaceType) {
        ParkingSpaceTypeConfig config = parkingSpaceService.getSpaceTypeConfig(spaceType);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @PostMapping("/space-types")
    public ResponseEntity<ParkingSpaceTypeConfig> createOrUpdateSpaceTypeConfig(
            @RequestBody ParkingSpaceTypeConfigRequest request) {
        ParkingSpaceTypeConfig config = parkingSpaceService.createOrUpdateSpaceTypeConfig(
                request.getSpaceType(),
                request.getDisplayName(),
                request.getBasePriceMultiplier(),
                request.isCanReserve(),
                request.getVehicleTypeRestriction(),
                request.getDescription()
        );
        return ResponseEntity.ok(config);
    }

    @GetMapping("/pre-calculation")
    public ResponseEntity<List<PreCalculationConfig>> getAllPreCalculationConfigs() {
        return ResponseEntity.ok(settlementService.getAllPreCalculationConfigs());
    }

    @PostMapping("/pre-calculation")
    public ResponseEntity<PreCalculationConfig> createOrUpdatePreCalculationConfig(
            @RequestBody PreCalculationConfigRequest request) {
        PreCalculationConfig config = settlementService.createOrUpdatePreCalculationConfig(
                request.getDurationCategory(),
                request.getDurationThresholdMinutes(),
                request.getPreCalculateBeforeExitMinutes(),
                request.getDescription()
        );
        return ResponseEntity.ok(config);
    }

    @PostMapping("/refresh-cache")
    public ResponseEntity<String> refreshAllCaches() {
        parkingSpaceService.refreshVehicleTypeCache();
        parkingSpaceService.refreshSpaceTypeCache();
        settlementService.refreshPreCalculationCache();
        return ResponseEntity.ok("缓存已刷新");
    }

    @GetMapping("/settlement-tasks/pending")
    public ResponseEntity<Long> getPendingTaskCount() {
        return ResponseEntity.ok(settlementTaskService.countPendingTasks());
    }

    public static class VehicleTypeConfigRequest {
        private String vehicleType;
        private String displayName;
        private int lockTimeoutSeconds;
        private int priority;
        private String description;

        public String getVehicleType() { return vehicleType; }
        public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public int getLockTimeoutSeconds() { return lockTimeoutSeconds; }
        public void setLockTimeoutSeconds(int lockTimeoutSeconds) { this.lockTimeoutSeconds = lockTimeoutSeconds; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class ParkingSpaceTypeConfigRequest {
        private String spaceType;
        private String displayName;
        private double basePriceMultiplier;
        private boolean canReserve;
        private String vehicleTypeRestriction;
        private String description;

        public String getSpaceType() { return spaceType; }
        public void setSpaceType(String spaceType) { this.spaceType = spaceType; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public double getBasePriceMultiplier() { return basePriceMultiplier; }
        public void setBasePriceMultiplier(double basePriceMultiplier) { this.basePriceMultiplier = basePriceMultiplier; }
        public boolean isCanReserve() { return canReserve; }
        public void setCanReserve(boolean canReserve) { this.canReserve = canReserve; }
        public String getVehicleTypeRestriction() { return vehicleTypeRestriction; }
        public void setVehicleTypeRestriction(String vehicleTypeRestriction) { this.vehicleTypeRestriction = vehicleTypeRestriction; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class PreCalculationConfigRequest {
        private String durationCategory;
        private int durationThresholdMinutes;
        private int preCalculateBeforeExitMinutes;
        private String description;

        public String getDurationCategory() { return durationCategory; }
        public void setDurationCategory(String durationCategory) { this.durationCategory = durationCategory; }
        public int getDurationThresholdMinutes() { return durationThresholdMinutes; }
        public void setDurationThresholdMinutes(int durationThresholdMinutes) { this.durationThresholdMinutes = durationThresholdMinutes; }
        public int getPreCalculateBeforeExitMinutes() { return preCalculateBeforeExitMinutes; }
        public void setPreCalculateBeforeExitMinutes(int preCalculateBeforeExitMinutes) { this.preCalculateBeforeExitMinutes = preCalculateBeforeExitMinutes; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
