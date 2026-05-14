package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.RestaurantTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, String> {
    Optional<RestaurantTable> findByTableNumber(String tableNumber);
    List<RestaurantTable> findByTableStatus(String tableStatus);
    List<RestaurantTable> findByTableCapacityGreaterThanEqual(int capacity);
    List<RestaurantTable> findByTableStatusAndTableCapacityGreaterThanEqual(String tableStatus, int capacity);
}
