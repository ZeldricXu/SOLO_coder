package com.fooddelivery.repository;

import com.fooddelivery.entity.Dish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DishRepository extends JpaRepository<Dish, String> {
    Optional<Dish> findByDishId(String dishId);
    List<Dish> findByRestaurantId(String restaurantId);
    List<Dish> findByRestaurantIdAndDishStatus(String restaurantId, String status);
    Optional<Dish> findByDishIdAndRestaurantId(String dishId, String restaurantId);
    boolean existsByDishId(String dishId);
}
