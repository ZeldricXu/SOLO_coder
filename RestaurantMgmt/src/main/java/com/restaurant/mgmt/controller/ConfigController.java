package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.config.DynamicDishCategoryConfig;
import com.restaurant.mgmt.config.DynamicPaymentTimeoutConfig;
import com.restaurant.mgmt.config.DynamicStockDeductionConfig;
import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.service.TableAllocationQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    @Autowired
    private DynamicPaymentTimeoutConfig paymentTimeoutConfig;

    @Autowired
    private DynamicStockDeductionConfig stockDeductionConfig;

    @Autowired
    private DynamicDishCategoryConfig dishCategoryConfig;

    @Autowired
    private TableAllocationQueueService tableQueueService;

    @GetMapping("/payment-timeout/tiers")
    public ApiResponse<List<DynamicPaymentTimeoutConfig.TimeoutTier>> getPaymentTimeoutTiers() {
        return ApiResponse.success(paymentTimeoutConfig.getTiers());
    }

    @PostMapping("/payment-timeout/tiers")
    public ApiResponse<DynamicPaymentTimeoutConfig.TimeoutTier> addTimeoutTier(
            @RequestBody DynamicPaymentTimeoutConfig.TimeoutTier tier) {
        paymentTimeoutConfig.addTier(tier);
        return ApiResponse.success(tier);
    }

    @PutMapping("/payment-timeout/tiers/{tierName}")
    public ApiResponse<String> updateTimeoutTier(
            @PathVariable String tierName,
            @RequestBody DynamicPaymentTimeoutConfig.TimeoutTier tier) {
        boolean updated = paymentTimeoutConfig.updateTier(tierName, tier);
        if (updated) {
            return ApiResponse.success("超时阈值更新成功");
        }
        return ApiResponse.error(404, "超时阈值配置不存在");
    }

    @DeleteMapping("/payment-timeout/tiers/{tierName}")
    public ApiResponse<String> removeTimeoutTier(@PathVariable String tierName) {
        boolean removed = paymentTimeoutConfig.removeTier(tierName);
        if (removed) {
            return ApiResponse.success("超时阈值删除成功");
        }
        return ApiResponse.error(404, "超时阈值配置不存在");
    }

    @GetMapping("/payment-timeout/tiers/by-amount/{amount}")
    public ApiResponse<Map<String, Object>> getTierByAmount(@PathVariable double amount) {
        Map<String, Object> result = new HashMap<>();
        DynamicPaymentTimeoutConfig.TimeoutTier tier = paymentTimeoutConfig.findTier(amount);
        result.put("orderAmount", amount);
        result.put("orderSize", paymentTimeoutConfig.getOrderSizeCategory(amount));
        result.put("timeoutMinutes", paymentTimeoutConfig.getTimeoutMinutes(amount));
        result.put("reminderMinutes", paymentTimeoutConfig.getReminderMinutes(amount));
        if (tier != null) {
            result.put("tier", tier);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/stock-deduction/strategies")
    public ApiResponse<List<DynamicStockDeductionConfig.DeductionStrategy>> getDeductionStrategies() {
        return ApiResponse.success(stockDeductionConfig.getStrategies());
    }

    @PostMapping("/stock-deduction/strategies")
    public ApiResponse<DynamicStockDeductionConfig.DeductionStrategy> addDeductionStrategy(
            @RequestBody DynamicStockDeductionConfig.DeductionStrategy strategy) {
        stockDeductionConfig.addStrategy(strategy);
        return ApiResponse.success(strategy);
    }

    @PutMapping("/stock-deduction/strategies/{strategyName}")
    public ApiResponse<String> updateDeductionStrategy(
            @PathVariable String strategyName,
            @RequestBody DynamicStockDeductionConfig.DeductionStrategy strategy) {
        boolean updated = stockDeductionConfig.updateStrategy(strategyName, strategy);
        if (updated) {
            return ApiResponse.success("扣减策略更新成功");
        }
        return ApiResponse.error(404, "扣减策略不存在");
    }

    @DeleteMapping("/stock-deduction/strategies/{strategyName}")
    public ApiResponse<String> removeDeductionStrategy(@PathVariable String strategyName) {
        boolean removed = stockDeductionConfig.removeStrategy(strategyName);
        if (removed) {
            return ApiResponse.success("扣减策略删除成功");
        }
        return ApiResponse.error(404, "扣减策略不存在");
    }

    @GetMapping("/stock-deduction/strategies/by-ingredient")
    public ApiResponse<Map<String, Object>> getStrategyByIngredient(
            @RequestParam String ingredientId,
            @RequestParam(required = false) String category) {
        Map<String, Object> result = new HashMap<>();
        String strategy = stockDeductionConfig.getDeductionStrategy(ingredientId, category);
        result.put("ingredientId", ingredientId);
        result.put("category", category);
        result.put("strategy", strategy);
        result.put("shouldPreDeduct", stockDeductionConfig.shouldPreDeduct(ingredientId, category));
        result.put("shouldConfirmDeduct", stockDeductionConfig.shouldConfirmDeduct(ingredientId, category));
        return ApiResponse.success(result);
    }

    @PostMapping("/stock-deduction/strategies/{strategyName}/ingredients/{ingredientId}")
    public ApiResponse<String> addIngredientToStrategy(
            @PathVariable String strategyName,
            @PathVariable String ingredientId) {
        stockDeductionConfig.addIngredientToStrategy(strategyName, ingredientId);
        return ApiResponse.success("食材已添加到策略");
    }

    @DeleteMapping("/stock-deduction/strategies/ingredients/{ingredientId}")
    public ApiResponse<String> removeIngredientFromStrategy(@PathVariable String ingredientId) {
        stockDeductionConfig.removeIngredientFromStrategy(ingredientId);
        return ApiResponse.success("食材已从策略中移除");
    }

    @PostMapping("/stock-deduction/strategies/{strategyName}/categories/{category}")
    public ApiResponse<String> addCategoryToStrategy(
            @PathVariable String strategyName,
            @PathVariable String category) {
        stockDeductionConfig.addCategoryToStrategy(strategyName, category);
        return ApiResponse.success("分类已添加到策略");
    }

    @DeleteMapping("/stock-deduction/strategies/categories/{category}")
    public ApiResponse<String> removeCategoryFromStrategy(@PathVariable String category) {
        stockDeductionConfig.removeCategoryFromStrategy(category);
        return ApiResponse.success("分类已从策略中移除");
    }

    @GetMapping("/dish-categories")
    public ApiResponse<List<DynamicDishCategoryConfig.DishCategory>> getDishCategories() {
        return ApiResponse.success(dishCategoryConfig.getCategories());
    }

    @GetMapping("/dish-categories/all")
    public ApiResponse<List<DynamicDishCategoryConfig.DishCategory>> getAllDishCategories() {
        return ApiResponse.success(dishCategoryConfig.getAllCategories());
    }

    @PostMapping("/dish-categories")
    public ApiResponse<DynamicDishCategoryConfig.DishCategory> addDishCategory(
            @RequestBody DynamicDishCategoryConfig.DishCategory category) {
        dishCategoryConfig.addCategory(category);
        return ApiResponse.success(category);
    }

    @PutMapping("/dish-categories/{code}")
    public ApiResponse<String> updateDishCategory(
            @PathVariable String code,
            @RequestBody DynamicDishCategoryConfig.DishCategory category) {
        boolean updated = dishCategoryConfig.updateCategory(code, category);
        if (updated) {
            return ApiResponse.success("菜品分类更新成功");
        }
        return ApiResponse.error(404, "菜品分类不存在");
    }

    @DeleteMapping("/dish-categories/{code}")
    public ApiResponse<String> removeDishCategory(@PathVariable String code) {
        boolean removed = dishCategoryConfig.removeCategory(code);
        if (removed) {
            return ApiResponse.success("菜品分类删除成功");
        }
        return ApiResponse.error(404, "菜品分类不存在");
    }

    @PostMapping("/dish-categories/{code}/enable")
    public ApiResponse<String> enableDishCategory(@PathVariable String code) {
        boolean enabled = dishCategoryConfig.enableCategory(code);
        if (enabled) {
            return ApiResponse.success("菜品分类已启用");
        }
        return ApiResponse.error(404, "菜品分类不存在");
    }

    @PostMapping("/dish-categories/{code}/disable")
    public ApiResponse<String> disableDishCategory(@PathVariable String code) {
        boolean disabled = dishCategoryConfig.disableCategory(code);
        if (disabled) {
            return ApiResponse.success("菜品分类已禁用");
        }
        return ApiResponse.error(404, "菜品分类不存在");
    }

    @GetMapping("/dish-categories/validate/{code}")
    public ApiResponse<Map<String, Object>> validateDishCategory(@PathVariable String code) {
        Map<String, Object> result = new HashMap<>();
        boolean valid = dishCategoryConfig.isValidCategory(code);
        result.put("code", code);
        result.put("valid", valid);
        if (valid) {
            DynamicDishCategoryConfig.DishCategory category = dishCategoryConfig.getCategory(code);
            result.put("name", category.getName());
            result.put("description", category.getDescription());
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/table-allocation/status")
    public ApiResponse<Map<String, Object>> getTableAllocationStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", tableQueueService.isQueueEnabled());
        result.put("queueSize", tableQueueService.getQueueSize());
        result.put("processingCount", tableQueueService.getProcessingCount());
        result.put("deadLetterCount", tableQueueService.getDeadLetterCount());
        return ApiResponse.success(result);
    }

    @GetMapping("/table-allocation/dead-letter")
    public ApiResponse<List<?>> getDeadLetterTasks() {
        return ApiResponse.success(tableQueueService.getDeadLetterTasks());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getConfigSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        Map<String, Object> paymentTimeout = new HashMap<>();
        paymentTimeout.put("tierCount", paymentTimeoutConfig.getTiers().size());
        paymentTimeout.put("defaultTimeout", paymentTimeoutConfig.getDefaultTimeoutMinutes());
        paymentTimeout.put("defaultReminder", paymentTimeoutConfig.getDefaultReminderMinutes());
        summary.put("paymentTimeout", paymentTimeout);
        
        Map<String, Object> stockDeduction = new HashMap<>();
        stockDeduction.put("strategyCount", stockDeductionConfig.getStrategies().size());
        stockDeduction.put("defaultStrategy", stockDeductionConfig.getDefaultStrategy());
        stockDeduction.put("warningLevelHighRatio", stockDeductionConfig.getWarningLevelHighRatio());
        stockDeduction.put("warningLevelMediumRatio", stockDeductionConfig.getWarningLevelMediumRatio());
        summary.put("stockDeduction", stockDeduction);
        
        Map<String, Object> dishCategory = new HashMap<>();
        dishCategory.put("categoryCount", dishCategoryConfig.getCategories().size());
        dishCategory.put("categories", dishCategoryConfig.getCategories().stream()
            .map(c -> c.getCode() + " - " + c.getName())
            .toList());
        summary.put("dishCategory", dishCategory);
        
        Map<String, Object> tableAllocation = new HashMap<>();
        tableAllocation.put("enabled", tableQueueService.isQueueEnabled());
        tableAllocation.put("queueSize", tableQueueService.getQueueSize());
        summary.put("tableAllocation", tableAllocation);
        
        return ApiResponse.success(summary);
    }
}
