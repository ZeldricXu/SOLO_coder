package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.DeviceType;
import com.deviceops.service.type.DeviceTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/device-types")
public class DeviceTypeController {

    @Autowired
    private DeviceTypeService deviceTypeService;

    @PostMapping
    public ApiResponse<DeviceType> createType(@RequestParam String typeCode,
                                               @RequestParam String typeName,
                                               @RequestParam(required = false) String typeDesc) {
        DeviceType type = deviceTypeService.createType(typeCode, typeName, typeDesc);
        return ApiResponse.success(type);
    }

    @GetMapping("/{typeId}")
    public ApiResponse<DeviceType> getTypeById(@PathVariable String typeId) {
        DeviceType type = deviceTypeService.getTypeById(typeId);
        return ApiResponse.success(type);
    }

    @GetMapping("/code/{typeCode}")
    public ApiResponse<DeviceType> getTypeByCode(@PathVariable String typeCode) {
        DeviceType type = deviceTypeService.getTypeByCode(typeCode)
                .orElseThrow(() -> new RuntimeException("设备类型不存在: " + typeCode));
        return ApiResponse.success(type);
    }

    @GetMapping
    public ApiResponse<List<DeviceType>> getAllTypes() {
        return ApiResponse.success(deviceTypeService.getAllTypes());
    }

    @PutMapping("/{typeId}")
    public ApiResponse<DeviceType> updateType(@PathVariable String typeId,
                                               @RequestParam(required = false) String typeName,
                                               @RequestParam(required = false) String typeDesc) {
        DeviceType type = deviceTypeService.updateType(typeId, typeName, typeDesc);
        return ApiResponse.success(type);
    }

    @DeleteMapping("/{typeId}")
    public ApiResponse<Void> deleteType(@PathVariable String typeId) {
        deviceTypeService.deleteType(typeId);
        return ApiResponse.success(null);
    }

    @PostMapping("/init")
    public ApiResponse<Void> initializeDefaultTypes() {
        deviceTypeService.initializeDefaultTypes();
        return ApiResponse.success(null);
    }
}
