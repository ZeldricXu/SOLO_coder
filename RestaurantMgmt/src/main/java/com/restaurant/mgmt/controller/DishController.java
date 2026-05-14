package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.model.Dish;
import com.restaurant.mgmt.service.DishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dishes")
public class DishController {

    @Autowired
    private DishService dishService;

    @PostMapping
    public ApiResponse<Dish> createDish(@RequestBody Dish dish) {
        Dish saved = dishService.createDish(dish);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<Dish>> getAllDishes() {
        List<Dish> dishes = dishService.getAllDishes();
        return ApiResponse.success(dishes);
    }

    @GetMapping("/{dishId}")
    public ApiResponse<Dish> getDish(@PathVariable String dishId) {
        Dish dish = dishService.getDishById(dishId);
        return ApiResponse.success(dish);
    }

    @GetMapping("/available")
    public ApiResponse<List<Dish>> getAvailableDishes() {
        List<Dish> dishes = dishService.getAvailableDishes();
        return ApiResponse.success(dishes);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<Dish>> getDishesByCategory(@PathVariable String category) {
        List<Dish> dishes = dishService.getDishesByCategory(category);
        return ApiResponse.success(dishes);
    }

    @GetMapping("/category/{category}/available")
    public ApiResponse<List<Dish>> getAvailableDishesByCategory(@PathVariable String category) {
        List<Dish> dishes = dishService.getAvailableDishesByCategory(category);
        return ApiResponse.success(dishes);
    }

    @PutMapping("/{dishId}")
    public ApiResponse<Dish> updateDish(
            @PathVariable String dishId,
            @RequestBody Dish dish) {
        Dish updated = dishService.updateDish(dishId, dish);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{dishId}")
    public ApiResponse<Void> deleteDish(@PathVariable String dishId) {
        dishService.deleteDish(dishId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{dishId}/available")
    public ApiResponse<Dish> setAvailable(@PathVariable String dishId) {
        Dish dish = dishService.setAvailable(dishId);
        return ApiResponse.success(dish);
    }

    @PostMapping("/{dishId}/sold-out")
    public ApiResponse<Dish> setSoldOut(@PathVariable String dishId) {
        Dish dish = dishService.setSoldOut(dishId);
        return ApiResponse.success(dish);
    }

    @PostMapping("/{dishId}/offline")
    public ApiResponse<Dish> setOffline(@PathVariable String dishId) {
        Dish dish = dishService.setOffline(dishId);
        return ApiResponse.success(dish);
    }

    @GetMapping("/{dishId}/check-available")
    public ApiResponse<Boolean> checkAvailable(@PathVariable String dishId) {
        boolean available = dishService.isDishAvailable(dishId);
        return ApiResponse.success(available);
    }
}
