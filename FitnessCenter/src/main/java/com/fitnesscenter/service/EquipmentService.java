package com.fitnesscenter.service;

import com.fitnesscenter.model.Equipment;
import com.fitnesscenter.repository.EquipmentRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;

    public EquipmentService(EquipmentRepository equipmentRepository) {
        this.equipmentRepository = equipmentRepository;
    }

    @Transactional
    public Equipment createEquipment(Equipment equipment) {
        equipment.setEquipmentId(IdGenerator.generateEquipmentId());
        equipment.setPurchaseDate(Instant.now());
        if (equipment.getEquipmentStatus() == null) {
            equipment.setEquipmentStatus("available");
        }
        return equipmentRepository.save(equipment);
    }

    @Transactional(readOnly = true)
    public Equipment getEquipmentById(String equipmentId) {
        return equipmentRepository.findByEquipmentId(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
    }

    @Transactional(readOnly = true)
    public List<Equipment> getAllEquipment() {
        return equipmentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Equipment> getEquipmentByGymId(String gymId) {
        return equipmentRepository.findByGymId(gymId);
    }

    @Transactional(readOnly = true)
    public List<Equipment> getEquipmentByStatus(String status) {
        return equipmentRepository.findByEquipmentStatus(status);
    }

    @Transactional
    public Equipment updateEquipmentStatus(String equipmentId, String status) {
        Equipment equipment = equipmentRepository.findByEquipmentId(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));

        equipment.setEquipmentStatus(status);
        if ("maintenance".equals(status)) {
            equipment.setLastMaintenance(Instant.now());
        }
        return equipmentRepository.save(equipment);
    }

    @Transactional
    public Equipment performMaintenance(String equipmentId) {
        Equipment equipment = equipmentRepository.findByEquipmentId(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));

        equipment.setLastMaintenance(Instant.now());
        equipment.setEquipmentStatus("available");
        return equipmentRepository.save(equipment);
    }

    @Transactional
    public Equipment updateEquipment(String equipmentId, Equipment equipmentDetails) {
        Equipment equipment = equipmentRepository.findByEquipmentId(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));

        if (equipmentDetails.getEquipmentName() != null) {
            equipment.setEquipmentName(equipmentDetails.getEquipmentName());
        }
        if (equipmentDetails.getEquipmentType() != null) {
            equipment.setEquipmentType(equipmentDetails.getEquipmentType());
        }
        if (equipmentDetails.getEquipmentStatus() != null) {
            equipment.setEquipmentStatus(equipmentDetails.getEquipmentStatus());
        }
        if (equipmentDetails.getGymId() != null) {
            equipment.setGymId(equipmentDetails.getGymId());
        }

        return equipmentRepository.save(equipment);
    }
}
