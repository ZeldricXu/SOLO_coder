package com.assetinventory.repository;

import com.assetinventory.entity.InventoryPerson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryPersonRepository extends JpaRepository<InventoryPerson, String> {

    List<InventoryPerson> findByPersonStatus(String personStatus);

    Optional<InventoryPerson> findByPersonId(String personId);
}
