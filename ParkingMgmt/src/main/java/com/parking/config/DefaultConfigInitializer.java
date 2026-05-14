package com.parking.config;

import com.parking.entity.PreCalculationConfig;
import com.parking.entity.ParkingSpaceTypeConfig;
import com.parking.entity.VehicleTypeConfig;
import com.parking.repository.PreCalculationConfigRepository;
import com.parking.repository.ParkingSpaceTypeConfigRepository;
import com.parking.repository.VehicleTypeConfigRepository;
import com.parking.service.SettlementTaskService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DefaultConfigInitializer {

    @Autowired
    private VehicleTypeConfigRepository vehicleTypeConfigRepository;

    @Autowired
    private ParkingSpaceTypeConfigRepository parkingSpaceTypeConfigRepository;

    @Autowired
    private PreCalculationConfigRepository preCalculationConfigRepository;

    @Autowired
    private SettlementTaskService settlementTaskService;

    @PostConstruct
    @Transactional
    public void initializeDefaultConfigs() {
        initializeVehicleTypeConfigs();
        initializeParkingSpaceTypeConfigs();
        initializePreCalculationConfigs();
        settlementTaskService.recoverAllPendingTasksOnStartup();
    }

    private void initializeVehicleTypeConfigs() {
        if (!vehicleTypeConfigRepository.existsByVehicleType("standard")) {
            VehicleTypeConfig standardConfig = new VehicleTypeConfig();
            standardConfig.setVehicleType("standard");
            standardConfig.setDisplayName("普通车辆");
            standardConfig.setLockTimeoutSeconds(120);
            standardConfig.setPriority(0);
            standardConfig.setDescription("普通车辆类型，长超时等待");
            standardConfig.setEnabled(true);
            vehicleTypeConfigRepository.save(standardConfig);
        }

        if (!vehicleTypeConfigRepository.existsByVehicleType("vip")) {
            VehicleTypeConfig vipConfig = new VehicleTypeConfig();
            vipConfig.setVehicleType("vip");
            vipConfig.setDisplayName("VIP车辆");
            vipConfig.setLockTimeoutSeconds(30);
            vipConfig.setPriority(10);
            vipConfig.setDescription("VIP车辆类型，短超时快速响应");
            vipConfig.setEnabled(true);
            vehicleTypeConfigRepository.save(vipConfig);
        }

        if (!vehicleTypeConfigRepository.existsByVehicleType("electric")) {
            VehicleTypeConfig electricConfig = new VehicleTypeConfig();
            electricConfig.setVehicleType("electric");
            electricConfig.setDisplayName("电动车");
            electricConfig.setLockTimeoutSeconds(90);
            electricConfig.setPriority(5);
            electricConfig.setDescription("电动车类型，中超时等待");
            electricConfig.setEnabled(true);
            vehicleTypeConfigRepository.save(electricConfig);
        }

        if (!vehicleTypeConfigRepository.existsByVehicleType("large")) {
            VehicleTypeConfig largeConfig = new VehicleTypeConfig();
            largeConfig.setVehicleType("large");
            largeConfig.setDisplayName("大型车辆");
            largeConfig.setLockTimeoutSeconds(150);
            largeConfig.setPriority(0);
            largeConfig.setDescription("大型车辆类型，长超时等待");
            largeConfig.setEnabled(true);
            vehicleTypeConfigRepository.save(largeConfig);
        }
    }

    private void initializeParkingSpaceTypeConfigs() {
        if (!parkingSpaceTypeConfigRepository.existsBySpaceType("standard")) {
            ParkingSpaceTypeConfig standardConfig = new ParkingSpaceTypeConfig();
            standardConfig.setSpaceType("standard");
            standardConfig.setDisplayName("标准车位");
            standardConfig.setBasePriceMultiplier(1.0);
            standardConfig.setCanReserve(true);
            standardConfig.setVehicleTypeRestriction(null);
            standardConfig.setDescription("普通标准车位，适用于所有类型车辆");
            standardConfig.setEnabled(true);
            parkingSpaceTypeConfigRepository.save(standardConfig);
        }

        if (!parkingSpaceTypeConfigRepository.existsBySpaceType("vip")) {
            ParkingSpaceTypeConfig vipConfig = new ParkingSpaceTypeConfig();
            vipConfig.setSpaceType("vip");
            vipConfig.setDisplayName("VIP车位");
            vipConfig.setBasePriceMultiplier(1.5);
            vipConfig.setCanReserve(true);
            vipConfig.setVehicleTypeRestriction("vip");
            vipConfig.setDescription("VIP专属车位，仅VIP车辆可使用");
            vipConfig.setEnabled(true);
            parkingSpaceTypeConfigRepository.save(vipConfig);
        }

        if (!parkingSpaceTypeConfigRepository.existsBySpaceType("electric")) {
            ParkingSpaceTypeConfig electricConfig = new ParkingSpaceTypeConfig();
            electricConfig.setSpaceType("electric");
            electricConfig.setDisplayName("充电车位");
            electricConfig.setBasePriceMultiplier(1.2);
            electricConfig.setCanReserve(true);
            electricConfig.setVehicleTypeRestriction("electric");
            electricConfig.setDescription("充电车位，配备充电桩");
            electricConfig.setEnabled(true);
            parkingSpaceTypeConfigRepository.save(electricConfig);
        }

        if (!parkingSpaceTypeConfigRepository.existsBySpaceType("large")) {
            ParkingSpaceTypeConfig largeConfig = new ParkingSpaceTypeConfig();
            largeConfig.setSpaceType("large");
            largeConfig.setDisplayName("大型车位");
            largeConfig.setBasePriceMultiplier(1.3);
            largeConfig.setCanReserve(true);
            largeConfig.setVehicleTypeRestriction("large");
            largeConfig.setDescription("大型车辆专用车位");
            largeConfig.setEnabled(true);
            parkingSpaceTypeConfigRepository.save(largeConfig);
        }

        if (!parkingSpaceTypeConfigRepository.existsBySpaceType("disabled")) {
            ParkingSpaceTypeConfig disabledConfig = new ParkingSpaceTypeConfig();
            disabledConfig.setSpaceType("disabled");
            disabledConfig.setDisplayName("无障碍车位");
            disabledConfig.setBasePriceMultiplier(0.8);
            disabledConfig.setCanReserve(false);
            disabledConfig.setVehicleTypeRestriction(null);
            disabledConfig.setDescription("无障碍专用车位，优惠收费");
            disabledConfig.setEnabled(true);
            parkingSpaceTypeConfigRepository.save(disabledConfig);
        }
    }

    private void initializePreCalculationConfigs() {
        if (!preCalculationConfigRepository.existsByDurationCategory("short")) {
            PreCalculationConfig shortConfig = new PreCalculationConfig();
            shortConfig.setDurationCategory("short");
            shortConfig.setDurationThresholdMinutes(60);
            shortConfig.setPreCalculateBeforeExitMinutes(10);
            shortConfig.setDescription("短时停车（60分钟内），出场前10分钟预计算");
            shortConfig.setEnabled(true);
            preCalculationConfigRepository.save(shortConfig);
        }

        if (!preCalculationConfigRepository.existsByDurationCategory("medium")) {
            PreCalculationConfig mediumConfig = new PreCalculationConfig();
            mediumConfig.setDurationCategory("medium");
            mediumConfig.setDurationThresholdMinutes(180);
            mediumConfig.setPreCalculateBeforeExitMinutes(30);
            mediumConfig.setDescription("中时停车（60-180分钟），出场前30分钟预计算");
            mediumConfig.setEnabled(true);
            preCalculationConfigRepository.save(mediumConfig);
        }

        if (!preCalculationConfigRepository.existsByDurationCategory("long")) {
            PreCalculationConfig longConfig = new PreCalculationConfig();
            longConfig.setDurationCategory("long");
            longConfig.setDurationThresholdMinutes(480);
            longConfig.setPreCalculateBeforeExitMinutes(60);
            longConfig.setDescription("长时停车（180-480分钟），出场前60分钟预计算");
            longConfig.setEnabled(true);
            preCalculationConfigRepository.save(longConfig);
        }

        if (!preCalculationConfigRepository.existsByDurationCategory("overnight")) {
            PreCalculationConfig overnightConfig = new PreCalculationConfig();
            overnightConfig.setDurationCategory("overnight");
            overnightConfig.setDurationThresholdMinutes(1440);
            overnightConfig.setPreCalculateBeforeExitMinutes(120);
            overnightConfig.setDescription("过夜停车（480分钟以上），出场前120分钟预计算");
            overnightConfig.setEnabled(true);
            preCalculationConfigRepository.save(overnightConfig);
        }
    }
}
