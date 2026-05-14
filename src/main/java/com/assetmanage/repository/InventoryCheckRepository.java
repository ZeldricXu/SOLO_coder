package com.assetmanage.repository;

import com.assetmanage.entity.InventoryCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryCheckRepository extends JpaRepository<InventoryCheck, String> {

    List<InventoryCheck> findByCheckStatus(String checkStatus);

    List<InventoryCheck> findByCheckDepartment(String checkDepartment);

    List<InventoryCheck> findByCheckType(String checkType);

    List<InventoryCheck> findAllByOrderByCreatedAtDesc();
}
