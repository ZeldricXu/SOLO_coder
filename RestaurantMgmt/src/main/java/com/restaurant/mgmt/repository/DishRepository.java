package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.Dish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DishRepository extends JpaRepository<Dish, String> {
    List<Dish> findByDishCategory(String dishCategory);
    List<Dish> findByDishStatus(String dishStatus);
    List<Dish> findByDishCategoryAndDishStatus(String dishCategory, String dishStatus);
    boolean existsByDishName(String dishName);
    long countByDishCategory(String dishCategory);
}
