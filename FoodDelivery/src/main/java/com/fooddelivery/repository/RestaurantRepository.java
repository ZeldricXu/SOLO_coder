package com.fooddelivery.repository;

import com.fooddelivery.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, String> {
    Optional<Restaurant> findByRestaurantId(String restaurantId);
    List<Restaurant> findByRestaurantRegion(String region);
    List<Restaurant> findByRestaurantStatus(String status);
    List<Restaurant> findByRestaurantType(String type);
    List<Restaurant> findByRestaurantRegionAndRestaurantStatus(String region, String status);
    boolean existsByRestaurantId(String restaurantId);
}
