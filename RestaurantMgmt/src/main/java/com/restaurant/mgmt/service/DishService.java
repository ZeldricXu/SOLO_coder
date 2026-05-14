package com.restaurant.mgmt.service;

import com.restaurant.mgmt.config.DynamicDishCategoryConfig;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Dish;
import com.restaurant.mgmt.repository.DishRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DishService {

    @Autowired
    private DishRepository dishRepository;

    @Autowired
    private DynamicDishCategoryConfig categoryConfig;

    public Dish createDish(Dish dish) {
        if (dish.getDishName() == null || dish.getDishName().trim().isEmpty()) {
            throw new BusinessException("菜品名称不能为空");
        }
        if (dish.getDishPrice() <= 0) {
            throw new BusinessException("菜品价格必须大于0");
        }
        if (dishRepository.existsByDishName(dish.getDishName())) {
            throw new BusinessException("菜品名称已存在");
        }
        
        if (dish.getDishCategory() != null && !dish.getDishCategory().trim().isEmpty()) {
            if (!categoryConfig.isValidCategory(dish.getDishCategory())) {
                throw new BusinessException("无效的菜品分类: " + dish.getDishCategory() + 
                    "，有效分类: " + String.join(", ", getValidCategoryCodes()));
            }
        }
        
        dish.setDishId(IdGenerator.generateDishId());
        dish.setCreatedAt(LocalDateTime.now());
        dish.setUpdatedAt(LocalDateTime.now());
        if (dish.getDishStatus() == null) {
            dish.setDishStatus("available");
        }
        return dishRepository.save(dish);
    }

    public Dish updateDish(String dishId, Dish dish) {
        Optional<Dish> existingOpt = dishRepository.findById(dishId);
        if (existingOpt.isEmpty()) {
            throw new BusinessException("菜品不存在");
        }
        
        Dish existing = existingOpt.get();
        if (dish.getDishName() != null) {
            existing.setDishName(dish.getDishName());
        }
        if (dish.getDishType() != null) {
            existing.setDishType(dish.getDishType());
        }
        if (dish.getDishPrice() > 0) {
            existing.setDishPrice(dish.getDishPrice());
        }
        if (dish.getDishCategory() != null) {
            if (!categoryConfig.isValidCategory(dish.getDishCategory())) {
                throw new BusinessException("无效的菜品分类: " + dish.getDishCategory());
            }
            existing.setDishCategory(dish.getDishCategory());
        }
        if (dish.getDishStatus() != null) {
            existing.setDishStatus(dish.getDishStatus());
        }
        if (dish.getDishImage() != null) {
            existing.setDishImage(dish.getDishImage());
        }
        if (dish.getDescription() != null) {
            existing.setDescription(dish.getDescription());
        }
        if (dish.getIngredients() != null) {
            existing.setIngredients(dish.getIngredients());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        
        return dishRepository.save(existing);
    }

    public void deleteDish(String dishId) {
        if (!dishRepository.existsById(dishId)) {
            throw new BusinessException("菜品不存在");
        }
        dishRepository.deleteById(dishId);
    }

    public Dish getDishById(String dishId) {
        return dishRepository.findById(dishId)
                .orElseThrow(() -> new BusinessException("菜品不存在"));
    }

    public List<Dish> getAllDishes() {
        return dishRepository.findAll();
    }

    public List<Dish> getDishesByCategory(String category) {
        return dishRepository.findByDishCategory(category);
    }

    public List<Dish> getAvailableDishes() {
        return dishRepository.findByDishStatus("available");
    }

    public List<Dish> getAvailableDishesByCategory(String category) {
        return dishRepository.findByDishCategoryAndDishStatus(category, "available");
    }

    @Transactional
    public Dish updateDishStatus(String dishId, String status) {
        Dish dish = getDishById(dishId);
        dish.setDishStatus(status);
        dish.setUpdatedAt(LocalDateTime.now());
        return dishRepository.save(dish);
    }

    @Transactional
    public Dish setAvailable(String dishId) {
        return updateDishStatus(dishId, "available");
    }

    @Transactional
    public Dish setSoldOut(String dishId) {
        return updateDishStatus(dishId, "sold_out");
    }

    @Transactional
    public Dish setOffline(String dishId) {
        return updateDishStatus(dishId, "offline");
    }

    public boolean isDishAvailable(String dishId) {
        Optional<Dish> dishOpt = dishRepository.findById(dishId);
        return dishOpt.isPresent() && "available".equals(dishOpt.get().getDishStatus());
    }

    public List<String> getValidCategoryCodes() {
        return categoryConfig.getCategories().stream()
            .map(DynamicDishCategoryConfig.DishCategory::getCode)
            .toList();
    }

    public String getCategoryDisplayName(String code) {
        return categoryConfig.getCategoryName(code);
    }

    public Map<String, Object> getCategoryStats() {
        Map<String, Object> stats = new HashMap<>();
        List<DynamicDishCategoryConfig.DishCategory> categories = categoryConfig.getCategories();
        
        for (DynamicDishCategoryConfig.DishCategory cat : categories) {
            Map<String, Object> catStats = new HashMap<>();
            catStats.put("name", cat.getName());
            catStats.put("code", cat.getCode());
            catStats.put("icon", cat.getIcon());
            catStats.put("description", cat.getDescription());
            catStats.put("dishCount", dishRepository.countByDishCategory(cat.getCode()));
            stats.put(cat.getCode(), catStats);
        }
        
        return stats;
    }
}
