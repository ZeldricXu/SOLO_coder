package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.model.Equipment;
import com.fitnesscenter.service.EquipmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {

    private final EquipmentService equipmentService;

    public EquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    @PostMapping("/create")
    public ApiResponse<Equipment> createEquipment(@RequestBody Equipment equipment) {
        Equipment savedEquipment = equipmentService.createEquipment(equipment);
        return ApiResponse.success(savedEquipment);
    }

    @GetMapping("/{equipmentId}")
    public ApiResponse<Equipment> getEquipmentById(@PathVariable String equipmentId) {
        Equipment equipment = equipmentService.getEquipmentById(equipmentId);
        return ApiResponse.success(equipment);
    }

    @GetMapping
    public ApiResponse<List<Equipment>> getAllEquipment() {
        List<Equipment> equipmentList = equipmentService.getAllEquipment();
        return ApiResponse.success(equipmentList);
    }

    @GetMapping("/gym/{gymId}")
    public ApiResponse<List<Equipment>> getEquipmentByGymId(@PathVariable String gymId) {
        List<Equipment> equipmentList = equipmentService.getEquipmentByGymId(gymId);
        return ApiResponse.success(equipmentList);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Equipment>> getEquipmentByStatus(@PathVariable String status) {
        List<Equipment> equipmentList = equipmentService.getEquipmentByStatus(status);
        return ApiResponse.success(equipmentList);
    }

    @PutMapping("/{equipmentId}")
    public ApiResponse<Equipment> updateEquipment(@PathVariable String equipmentId, @RequestBody Equipment equipmentDetails) {
        Equipment equipment = equipmentService.updateEquipment(equipmentId, equipmentDetails);
        return ApiResponse.success(equipment);
    }

    @PutMapping("/{equipmentId}/status")
    public ApiResponse<Equipment> updateEquipmentStatus(@PathVariable String equipmentId, @RequestParam String status) {
        Equipment equipment = equipmentService.updateEquipmentStatus(equipmentId, status);
        return ApiResponse.success(equipment);
    }

    @PostMapping("/{equipmentId}/maintenance")
    public ApiResponse<Equipment> performMaintenance(@PathVariable String equipmentId) {
        Equipment equipment = equipmentService.performMaintenance(equipmentId);
        return ApiResponse.success(equipment);
    }
}
