package com.deviceops.service.type;

import com.deviceops.config.model.DeviceTypeConfig;
import com.deviceops.entity.DeviceType;
import com.deviceops.repository.DeviceTypeRepository;
import com.deviceops.service.config.DynamicConfigService;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DeviceTypeService {

    @Autowired
    private DeviceTypeRepository deviceTypeRepository;

    @Autowired
    private DynamicConfigService dynamicConfigService;

    @Transactional
    public DeviceType createType(String typeCode, String typeName, String typeDesc) {
        if (deviceTypeRepository.existsByTypeCode(typeCode)) {
            throw new RuntimeException("设备类型已存在: " + typeCode);
        }

        DeviceType type = new DeviceType();
        type.setTypeId(IdGenerator.generateTypeId());
        type.setTypeCode(typeCode);
        type.setTypeName(typeName);
        type.setTypeDesc(typeDesc);
        return deviceTypeRepository.save(type);
    }

    public Optional<DeviceType> getTypeByCode(String typeCode) {
        return deviceTypeRepository.findByTypeCode(typeCode);
    }

    public DeviceType getTypeById(String typeId) {
        return deviceTypeRepository.findById(typeId)
                .orElseThrow(() -> new RuntimeException("设备类型不存在: " + typeId));
    }

    public List<DeviceType> getAllTypes() {
        return deviceTypeRepository.findAll();
    }

    public boolean existsByCode(String typeCode) {
        return deviceTypeRepository.existsByTypeCode(typeCode);
    }

    @Transactional
    public DeviceType updateType(String typeId, String typeName, String typeDesc) {
        DeviceType type = getTypeById(typeId);
        if (typeName != null) {
            type.setTypeName(typeName);
        }
        if (typeDesc != null) {
            type.setTypeDesc(typeDesc);
        }
        return deviceTypeRepository.save(type);
    }

    @Transactional
    public void deleteType(String typeId) {
        if (!deviceTypeRepository.existsById(typeId)) {
            throw new RuntimeException("设备类型不存在: " + typeId);
        }
        deviceTypeRepository.deleteById(typeId);
    }

    public void initializeDefaultTypes() {
        if (!deviceTypeRepository.existsByTypeCode("server")) {
            createType("server", "服务器", "服务器类设备");
            dynamicConfigService.addDeviceTypeConfig(
                new DeviceTypeConfig("server", "服务器", "服务器类设备", "compute", true, "server-icon")
            );
        }
        if (!deviceTypeRepository.existsByTypeCode("network")) {
            createType("network", "网络设备", "网络设备如交换机、路由器等");
            dynamicConfigService.addDeviceTypeConfig(
                new DeviceTypeConfig("network", "网络设备", "网络设备如交换机、路由器等", "network", true, "network-icon")
            );
        }
        if (!deviceTypeRepository.existsByTypeCode("storage")) {
            createType("storage", "存储设备", "存储设备如磁盘阵列等");
            dynamicConfigService.addDeviceTypeConfig(
                new DeviceTypeConfig("storage", "存储设备", "存储设备如磁盘阵列等", "storage", true, "storage-icon")
            );
        }
        if (!deviceTypeRepository.existsByTypeCode("workstation")) {
            createType("workstation", "工作站", "工作站类设备");
            dynamicConfigService.addDeviceTypeConfig(
                new DeviceTypeConfig("workstation", "工作站", "工作站类设备", "compute", true, "workstation-icon")
            );
        }
        if (!deviceTypeRepository.existsByTypeCode("printer")) {
            createType("printer", "打印机", "打印机类设备");
            dynamicConfigService.addDeviceTypeConfig(
                new DeviceTypeConfig("printer", "打印机", "打印机类设备", "peripheral", true, "printer-icon")
            );
        }
    }

    @Transactional
    public DeviceType addCustomDeviceType(DeviceTypeConfig config) {
        if (deviceTypeRepository.existsByTypeCode(config.getTypeCode())) {
            throw new RuntimeException("设备类型已存在: " + config.getTypeCode());
        }

        DeviceType type = new DeviceType();
        type.setTypeId(IdGenerator.generateTypeId());
        type.setTypeCode(config.getTypeCode());
        type.setTypeName(config.getTypeName());
        type.setTypeDesc(config.getTypeDesc());
        DeviceType saved = deviceTypeRepository.save(type);

        dynamicConfigService.addDeviceTypeConfig(config);

        return saved;
    }

    @Transactional
    public void removeDeviceType(String typeCode) {
        if (!deviceTypeRepository.existsByTypeCode(typeCode)) {
            throw new RuntimeException("设备类型不存在: " + typeCode);
        }
        deviceTypeRepository.deleteByTypeCode(typeCode);
        dynamicConfigService.removeDeviceTypeConfig(typeCode);
    }

    @Transactional
    public DeviceType updateDeviceType(DeviceTypeConfig config) {
        Optional<DeviceType> existing = deviceTypeRepository.findByTypeCode(config.getTypeCode());
        if (existing.isEmpty()) {
            throw new RuntimeException("设备类型不存在: " + config.getTypeCode());
        }

        DeviceType type = existing.get();
        type.setTypeName(config.getTypeName());
        type.setTypeDesc(config.getTypeDesc());
        DeviceType saved = deviceTypeRepository.save(type);

        dynamicConfigService.updateDeviceTypeConfig(config);

        return saved;
    }

    public List<DeviceType> getAllConfiguredTypes() {
        List<DeviceTypeConfig> configs = new ArrayList<>(dynamicConfigService.getAllDeviceTypeConfigs());
        List<DeviceType> types = new ArrayList<>();

        for (DeviceTypeConfig config : configs) {
            DeviceType type = new DeviceType();
            type.setTypeCode(config.getTypeCode());
            type.setTypeName(config.getTypeName());
            type.setTypeDesc(config.getTypeDesc());
            types.add(type);
        }

        if (types.isEmpty()) {
            types = deviceTypeRepository.findAll();
        }

        return types;
    }

    public boolean isTypeEnabled(String typeCode) {
        return dynamicConfigService.isDeviceTypeEnabled(typeCode);
    }
}
