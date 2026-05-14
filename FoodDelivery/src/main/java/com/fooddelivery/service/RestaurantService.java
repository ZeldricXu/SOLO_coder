package com.fooddelivery.service;

import com.fooddelivery.entity.Dish;
import com.fooddelivery.entity.Restaurant;
import com.fooddelivery.repository.DishRepository;
import com.fooddelivery.repository.RestaurantRepository;
import com.fooddelivery.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class RestaurantService {

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private DishRepository dishRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RestaurantTypeService restaurantTypeService;

    @Transactional
    public Restaurant createRestaurant(Restaurant restaurant) {
        restaurant.setRestaurantId(IdGenerator.generateRestaurantId());
        if (restaurant.getRestaurantType() != null && !restaurantTypeService.isValidType(restaurant.getRestaurantType())) {
            log.warn("餐厅类型无效: {}, 使用默认类型", restaurant.getRestaurantType());
            restaurant.setRestaurantType(restaurantTypeService.getDefaultType().getCode());
        }
        Restaurant saved = restaurantRepository.save(restaurant);
        historyService.recordHistory("restaurant", saved.getRestaurantId(), "create", "创建餐厅：" + saved.getRestaurantName());
        return saved;
    }

    public Optional<Restaurant> getRestaurantById(String restaurantId) {
        return restaurantRepository.findByRestaurantId(restaurantId);
    }

    public List<Restaurant> getAllRestaurants() {
        return restaurantRepository.findAll();
    }

    public List<Restaurant> getRestaurantsByRegion(String region) {
        return restaurantRepository.findByRestaurantRegion(region);
    }

    public List<Restaurant> getRestaurantsByStatus(String status) {
        return restaurantRepository.findByRestaurantStatus(status);
    }

    public List<Restaurant> getRestaurantsByType(String type) {
        return restaurantRepository.findByRestaurantType(type);
    }

    public List<Restaurant> getAvailableRestaurants(String region) {
        return restaurantRepository.findByRestaurantRegionAndRestaurantStatus(region, "open");
    }

    @Transactional
    public Restaurant updateRestaurant(String restaurantId, Restaurant restaurant) {
        Restaurant existing = restaurantRepository.findByRestaurantId(restaurantId)
                .orElseThrow(() -> new RuntimeException("餐厅不存在"));
        existing.setRestaurantName(restaurant.getRestaurantName() != null ? restaurant.getRestaurantName() : existing.getRestaurantName());
        if (restaurant.getRestaurantType() != null) {
            if (restaurantTypeService.isValidType(restaurant.getRestaurantType())) {
                existing.setRestaurantType(restaurant.getRestaurantType());
            } else {
                log.warn("餐厅类型无效: {}, 保持原有类型", restaurant.getRestaurantType());
            }
        }
        existing.setRestaurantAddress(restaurant.getRestaurantAddress() != null ? restaurant.getRestaurantAddress() : existing.getRestaurantAddress());
        existing.setRestaurantRegion(restaurant.getRestaurantRegion() != null ? restaurant.getRestaurantRegion() : existing.getRestaurantRegion());
        existing.setRestaurantStatus(restaurant.getRestaurantStatus() != null ? restaurant.getRestaurantStatus() : existing.getRestaurantStatus());
        Restaurant saved = restaurantRepository.save(existing);
        historyService.recordHistory("restaurant", saved.getRestaurantId(), "update", "更新餐厅信息");
        return saved;
    }

    @Transactional
    public Restaurant updateRestaurantStatus(String restaurantId, String status) {
        Restaurant restaurant = restaurantRepository.findByRestaurantId(restaurantId)
                .orElseThrow(() -> new RuntimeException("餐厅不存在"));
        restaurant.setRestaurantStatus(status);
        Restaurant saved = restaurantRepository.save(restaurant);
        historyService.recordHistory("restaurant", saved.getRestaurantId(), "status_change", "更新餐厅状态为：" + status);
        return saved;
    }

    @Transactional
    public Restaurant updateRestaurantType(String restaurantId, String newType) {
        if (!restaurantTypeService.isValidType(newType)) {
            throw new RuntimeException("无效的餐厅类型: " + newType);
        }
        Restaurant restaurant = restaurantRepository.findByRestaurantId(restaurantId)
                .orElseThrow(() -> new RuntimeException("餐厅不存在"));
        restaurant.setRestaurantType(newType);
        Restaurant saved = restaurantRepository.save(restaurant);
        historyService.recordHistory("restaurant", saved.getRestaurantId(), "type_change", 
                "更新餐厅类型为：" + restaurantTypeService.getTypeName(newType));
        return saved;
    }

    @Transactional
    public void incrementOrderCount(String restaurantId) {
        Restaurant restaurant = restaurantRepository.findByRestaurantId(restaurantId)
                .orElseThrow(() -> new RuntimeException("餐厅不存在"));
        int current = restaurant.getRestaurantOrderCount() != null ? restaurant.getRestaurantOrderCount() : 0;
        restaurant.setRestaurantOrderCount(current + 1);
        restaurantRepository.save(restaurant);
    }

    @Transactional
    public Restaurant updateRestaurantRating(String restaurantId, Integer newRating) {
        Restaurant restaurant = restaurantRepository.findByRestaurantId(restaurantId)
                .orElseThrow(() -> new RuntimeException("餐厅不存在"));
        int currentCount = restaurant.getRestaurantRatingCount() != null ? restaurant.getRestaurantRatingCount() : 0;
        double currentRating = restaurant.getRestaurantRating() != null ? restaurant.getRestaurantRating() : 0.0;
        double totalRating = currentRating * currentCount;
        int newCount = currentCount + 1;
        double newAvgRating = (totalRating + newRating) / newCount;
        restaurant.setRestaurantRating(newAvgRating);
        restaurant.setRestaurantRatingCount(newCount);
        Restaurant saved = restaurantRepository.save(restaurant);
        historyService.recordHistory("restaurant", saved.getRestaurantId(), "rating_update", "更新餐厅评分，新评分：" + newAvgRating);
        return saved;
    }

    @Transactional
    public Dish createDish(String restaurantId, Dish dish) {
        if (!restaurantRepository.existsByRestaurantId(restaurantId)) {
            throw new RuntimeException("餐厅不存在");
        }
        dish.setDishId(IdGenerator.generateDishId());
        dish.setRestaurantId(restaurantId);
        Dish saved = dishRepository.save(dish);
        historyService.recordHistory("dish", saved.getDishId(), "create", "创建菜品：" + saved.getDishName());
        return saved;
    }

    public Optional<Dish> getDishById(String dishId) {
        return dishRepository.findByDishId(dishId);
    }

    public List<Dish> getDishesByRestaurant(String restaurantId) {
        return dishRepository.findByRestaurantId(restaurantId);
    }

    public List<Dish> getAvailableDishesByRestaurant(String restaurantId) {
        return dishRepository.findByRestaurantIdAndDishStatus(restaurantId, "active");
    }

    public Optional<Dish> getDishByRestaurantAndId(String restaurantId, String dishId) {
        return dishRepository.findByDishIdAndRestaurantId(dishId, restaurantId);
    }

    public String getRestaurantTypeName(String typeCode) {
        return restaurantTypeService.getTypeName(typeCode);
    }
}
