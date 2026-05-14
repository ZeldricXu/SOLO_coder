package com.fooddelivery.controller;

import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.entity.Restaurant;
import com.fooddelivery.entity.Dish;
import com.fooddelivery.service.RestaurantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/restaurants")
public class RestaurantController {

    @Autowired
    private RestaurantService restaurantService;

    @PostMapping
    public ResponseEntity<ApiResponse<Restaurant>> createRestaurant(@RequestBody Restaurant restaurant) {
        Restaurant saved = restaurantService.createRestaurant(restaurant);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Restaurant>>> getAllRestaurants() {
        List<Restaurant> restaurants = restaurantService.getAllRestaurants();
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @GetMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<Restaurant>> getRestaurant(@PathVariable String restaurantId) {
        Optional<Restaurant> restaurant = restaurantService.getRestaurantById(restaurantId);
        if (restaurant.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(restaurant.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "餐厅不存在"));
    }

    @PutMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<Restaurant>> updateRestaurant(@PathVariable String restaurantId,
                                                                    @RequestBody Restaurant restaurant) {
        Restaurant updated = restaurantService.updateRestaurant(restaurantId, restaurant);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PutMapping("/{restaurantId}/status")
    public ResponseEntity<ApiResponse<Restaurant>> updateStatus(@PathVariable String restaurantId,
                                                                @RequestParam String status) {
        Restaurant updated = restaurantService.updateRestaurantStatus(restaurantId, status);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PostMapping("/{restaurantId}/dishes")
    public ResponseEntity<ApiResponse<Dish>> createDish(@PathVariable String restaurantId,
                                                        @RequestBody Dish dish) {
        Dish saved = restaurantService.createDish(restaurantId, dish);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping("/{restaurantId}/dishes")
    public ResponseEntity<ApiResponse<List<Dish>>> getDishes(@PathVariable String restaurantId) {
        List<Dish> dishes = restaurantService.getDishesByRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(dishes));
    }

    @GetMapping("/{restaurantId}/dishes/available")
    public ResponseEntity<ApiResponse<List<Dish>>> getAvailableDishes(@PathVariable String restaurantId) {
        List<Dish> dishes = restaurantService.getAvailableDishesByRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(dishes));
    }
}
