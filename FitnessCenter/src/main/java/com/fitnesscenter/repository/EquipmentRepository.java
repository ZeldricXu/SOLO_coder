package com.fitnesscenter.repository;

import com.fitnesscenter.model.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EquipmentRepository extends JpaRepository<Equipment, String> {
    
    Optional<Equipment> findByEquipmentId(String equipmentId);
    
    List<Equipment> findByEquipmentStatus(String equipmentStatus);
    
    List<Equipment> findByEquipmentType(String equipmentType);
    
    List<Equipment> findByGymId(String gymId);
    
    List<Equipment> findByGymIdAndEquipmentStatus(String gymId, String equipmentStatus);
    
    boolean existsByEquipmentId(String equipmentId);
}
